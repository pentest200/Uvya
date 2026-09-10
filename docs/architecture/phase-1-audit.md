# Uvya Phase 1 data-foundation audit

Audit performed before the Phase 1 additions. The existing authentication migration is
`V1__authentication_foundation.sql`; the additions are in
`V2__data_foundation.sql`. No Phase 2 table was renamed or duplicated.

## Existing requirements satisfied by Phase 2

| Requirement | Existing implementation | Result |
| --- | --- | --- |
| users | `app_users` + `UserEntity` + `UserRepository` | Satisfied; retained as the account table name |
| devices | `devices` + `DeviceEntity` + `DeviceRepository` | Satisfied and reused |
| sessions | `auth_sessions` + `AuthSessionEntity` + `AuthSessionRepository` | Satisfied; retained to preserve refresh rotation |
| audit logs | `auth_audit_logs` + `AuthAuditLogEntity` + `AuthAuditLogRepository` | Satisfied and reused for auth and data audit events |
| password/account controls | status, failed-attempt count, lock expiry | Satisfied by Phase 2 |
| refresh-token uniqueness | unique HMAC digest in `auth_sessions` | Satisfied by Phase 2 |
| session ownership | authenticated user predicates in service/repository calls | Satisfied by Phase 2 |

## Newly implemented requirements

- `chats` and `chat_members`, including membership-role and membership-access indexes.
- `messages` with all required fields, explicit per-chat sequence, foreign keys, checks,
  and `(chat_id, client_message_id, sender_id)` uniqueness.
- `message_reactions`, `message_versions`, `user_inbox`, `read_states`, and
  `blocked_users`.
- `idempotency_keys` for user-scoped request replay protection.
- `outbox_events`, its repository, event envelope, and publisher abstraction.
- Atomic message creation: the chat counter lock, message insert, idempotency record,
  and `message.created` outbox insert execute in one transaction.
- Versioned JSON event schemas and documentation for all Phase 1 event types.
- Integration coverage for persistence, ordering, replay prevention, relationships,
  outbox atomicity, audit reuse, and the important repository access paths.

## Index rationale

| Access path | Supporting key/index |
| --- | --- |
| Fetch messages in a chat by sequence | Unique `(chat_id, sequence)` key; its ordered unique index is sufficient, so no duplicate index is created. |
| Fetch a user's chats and membership lookup | `chat_members` primary key `(chat_id, user_id)` plus `(user_id, chat_id)`. |
| Read state lookup | `read_states` primary key `(chat_id, user_id)` plus `(user_id, chat_id)` for user-centric sync. |
| Client-message idempotency lookup | Unique `(chat_id, client_message_id, sender_id)`. User-scoped request replay uses unique `(user_id, idempotency_key)`. |
| Outbox polling | Partial `(available_at, occurred_at, event_id)` index for unpublished rows; aggregate lookup has its own index. |
| Session lookup | Existing Phase 2 indexes on user and device, plus unique refresh-token digest. |
| Audit lookup | Existing Phase 2 indexes on `(user_id, occurred_at)` and `(event_type, occurred_at)`. |

Indexes that duplicate a primary/unique key were intentionally omitted to avoid extra
write amplification.

## Compatibility findings

The specification uses the conceptual names `users`, `sessions`, and `audit_logs`, while
Phase 2 uses `app_users`, `auth_sessions`, and `auth_audit_logs`. Creating aliases or
parallel tables would split ownership and break the authentication mappings, so the
existing names are the canonical physical tables. All new foreign keys point to those
existing Phase 2 tables. Their nullable password hash and nullable audit actor fields are
left unchanged because those are valid for the existing registration, failed-login, and
unknown-account flows.

The message sequence is allocated by locking the owning chat row and incrementing
`chats.last_message_sequence` inside the same transaction as the message and outbox
writes. The unique `(chat_id, sequence)` constraint is the database backstop; timestamps
are never used for ordering.

V2 also adds an additive `(devices.id, devices.user_id)` key so the message foreign key
enforces that `sender_device_id` belongs to `sender_id` at the database boundary, in
addition to the application-level ownership check.

## Deliberately deferred

- Kafka consumers and consumer retry orchestration remain deferred.
- No frontend or message HTTP API is added in this phase.
- ScyllaDB remains deferred per ADR-002.
- A production outbox worker schedule and lease protocol will be added with the first
  Kafka publisher deployment; the current publisher interface is intentionally injectable.
