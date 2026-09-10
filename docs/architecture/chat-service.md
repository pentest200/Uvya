# Uvya Chat Service

The Chat Service owns chat metadata, membership, chat settings, and membership policy.
It reuses the Phase 1 `chats` and `chat_members` tables and extends them additively in
`V4__chat_service_foundation.sql`.

## Chat types and roles

The supported chat types are `DIRECT`, `GROUP`, and `CHANNEL`. Every chat has one
`OWNER`; group and channel memberships may additionally be `ADMIN`, `MODERATOR`,
`MEMBER`, or `RESTRICTED`.

- Direct chats require exactly one other initial member.
- Groups allow members to post unless they are restricted or banned.
- Channels default to owner/admin/moderator posting. Owners can enable member posting
  through chat settings.
- Restricted members can view an active chat but cannot post or manage membership.
- Banned members are denied chat access and cannot be re-added until the ban expires or
  is removed by a future moderation workflow.

## Authorization policy

`ChatAuthorizationPolicy` is the single policy boundary. It validates, in order:

1. account status;
2. device ownership and revocation state;
3. session ownership, expiry, revocation, and device binding;
4. active chat membership and ban state;
5. role permission for the requested action;
6. channel posting settings for message creation.

The policy uses one membership lookup per decision. It never loads the complete member
list for authorization.

## Pagination and concurrency

`GET /v1/chats` and `GET /v1/chats/{chatId}/members` use database-backed `Pageable`
queries. Chat details return a member count and the viewer's role rather than loading
all members. Member additions lock the chat row and then the target membership row;
the database primary key remains the final duplicate-add backstop.

## Additional state

`chat_settings` stores member posting, discoverability, and slow-mode settings.
`chat_members` stores mute, archive, and ban timestamps. `chat_pinned_messages` stores
chat/message pin ownership and time. Chat responses expose bounded, newest-first pinned
message IDs (up to 50) and never materialize an unbounded membership collection.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/v1/chats` | Create a direct chat, group, or channel |
| GET | `/v1/chats` | Paginated active chats for the authenticated user |
| GET | `/v1/chats/{chatId}` | Read chat metadata for an active member |
| PATCH | `/v1/chats/{chatId}` | Update title/settings with owner/admin permission |
| POST | `/v1/chats/{chatId}/members` | Add a member with an allowed role |
| DELETE | `/v1/chats/{chatId}/members/{userId}` | Remove another member |
| POST | `/v1/chats/{chatId}/leave` | Leave a chat; owners must transfer ownership first |
| GET | `/v1/chats/{chatId}/members` | Paginated active membership list |

Mutating bearer-token endpoints use the existing stateless bearer transport and are
explicitly exempted from cookie CSRF checks. All mutations accept an `Idempotency-Key`;
chat creation persists a request hash and returns the original chat on an identical
retry.
