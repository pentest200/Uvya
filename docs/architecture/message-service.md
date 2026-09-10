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

Forwarding stores only the source message ID. The sender must be an active member of the source chat, and deleted source messages cannot be forwarded. Reply references remain within the destination chat.

## API shape

- `POST /v1/chats/{chatId}/messages` accepts text, `clientMessageId`, optional reply/forward IDs, and a future-ready empty `attachments` array.
- `GET /v1/chats/{chatId}/messages?before={sequence}&size={1..100}` returns bounded cursor pages.
- `PATCH /v1/chats/{chatId}/messages/{messageId}` accepts text/body and optional `expectedVersion`; only the sender can edit.
- `DELETE /v1/chats/{chatId}/messages/{messageId}` creates a tombstone; the sender or a chat owner/admin/moderator can delete.

Mutation endpoints use the existing bearer-token/session security model and are included in the stateless token transport's CSRF policy.
