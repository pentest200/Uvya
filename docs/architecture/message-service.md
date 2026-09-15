# Message Service

The Message API is exposed by the API gateway at `/v1/chats/{chatId}/messages`.

## Persistence flow

1. The JWT subject, device, and session claims are converted into a `ChatAccessContext`.
2. `ChatAuthorizationPolicy` validates the user, device, session, membership, ban state, role, and channel posting policy.
3. `MessageService` locks the chat row, checks the client/idempotency key, allocates the next per-chat sequence, persists the message, inbox rows, idempotency record, audit record, and `message.created` outbox event in one transaction.
4. The response is returned only after the transaction commits.

The unique `(chat_id, sequence)` constraint and locked chat counter provide an explicit ordering key. The unique `(chat_id, client_message_id, sender_id)` constraint protects retries even if the application is concurrently invoked.

## History and mutations

History is cursor based. `before` is the last sequence visible to the caller; the query reads lower sequences with a bounded `LIMIT`, then returns the page in chronological order and a `nextCursor` when older data remains. No offset or full-chat materialization is used.

Edits lock the message row, optionally validate `expectedVersion`, update the message, insert a `message_versions` row, and write `message.edited` in the same transaction. Deletes lock the row and clear the body while retaining the identity, sequence, timestamps, and status as a tombstone. The response and events never expose a deleted body.

Forwarding stores the source message ID plus immutable origin chat/sender/time metadata. Attachments are resolved through the source reference rather than copied into the forwarded message. The sender must be an active member of the source chat, and deleted source messages cannot be forwarded. Reply references remain within the destination chat; a soft-deleted reply target remains valid and is rendered as a tombstone. Thread replies store a root message ID and use the same per-chat sequence cursor for bounded pagination.

Reactions are dedicated `(message_id, user_id, reaction_type)` rows. Add/remove operations lock the message row only to serialize the transition check, then write or delete the reaction row and publish aggregate counts plus the actor's reaction set. The message record is never rewritten for a reaction. Pin state is also a dedicated chat/message row and has idempotent pin/unpin events.

## Fan-out and delivery

The `message.created` event is consumed by the separate `uvya-fanout` Kafka consumer
group. Direct chats and groups at or below the configured member limit use fan-out on
write: participant inbox rows are created with `PERSISTED`, and the worker routes
`message.new` to every active recipient device plus the sender's other devices. Larger
groups use fan-out on read: no inbox row or offline notification is created for every
member; active members are resolved when the event is consumed, while offline members
use durable message/read-state cursors.

Redis contains only active connection metadata and route signals. It is never an
offline message queue. Offline direct/small-group recipients get a durable
`notification.requested` outbox event instead.

An online route moves an inbox row to `PENDING`. The client acknowledges the network
delivery with `message.delivered`; the API locks the row, moves it to `DELIVERED`, and
writes a durable `message.delivered` outbox event. Repeated acknowledgements are
no-ops. `READ` is driven by the monotonic read state, and failed route attempts are
retried with the durable attempt counter before becoming `FAILED`. Network delivery
never changes message persistence, which is already committed before fan-out begins.

## API shape

- `POST /v1/chats/{chatId}/messages` accepts text, `clientMessageId`, optional reply/forward IDs, and a future-ready empty `attachments` array.
- `GET /v1/chats/{chatId}/messages?before={sequence}&size={1..100}` returns bounded cursor pages.
- `PATCH /v1/chats/{chatId}/messages/{messageId}` accepts text/body and optional `expectedVersion`; only the sender can edit.
- `DELETE /v1/chats/{chatId}/messages/{messageId}` creates a tombstone; the sender or a chat owner/admin/moderator can delete.
- `POST /v1/chats/{chatId}/messages/{messageId}/delivery` acknowledges receipt on a device; it is idempotent and does not persist the message.
- `GET /v1/chats/{chatId}/messages/{messageId}/thread?before={sequence}&size={1..100}` returns the root and cursor-paged thread messages.
- `PUT|DELETE /v1/messages/{messageId}/reactions/{emoji}` changes per-user reaction state and returns aggregate counts.
- `GET /v1/messages/{messageId}/reactions` returns aggregate counts and the authenticated user's reactions.
- `PUT|DELETE /v1/chats/{chatId}/messages/{messageId}/pin` changes dedicated pin state; `GET /v1/chats/{chatId}/pinned-messages` lists pins.

Mutation endpoints use the existing bearer-token/session security model and are included in the stateless token transport's CSRF policy.
