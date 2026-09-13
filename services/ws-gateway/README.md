# WebSocket gateway

The Go process is Uvya's realtime edge. It owns live socket objects and ephemeral
connection metadata only; PostgreSQL and the API gateway remain the source of truth
for durable messages.

## Connection and authentication

`GET /ws` (also available at `/v1/ws`) requires an RSA-signed access JWT. The gateway
accepts `Authorization: Bearer <token>` or the browser-compatible `access_token` query
parameter. The token must contain the same `sub`, `did`, and `sid` claims used by the
API gateway. The device is also supplied as `X-Device-ID` or `deviceId` and must match
the token's `did` claim.

Configure `UVYA_JWT_PUBLIC_KEY_BASE64` or `JWT_PUBLIC_KEY_FILE` in deployments. For
local Compose, the gateway can load the generated API gateway key from
`JWT_JWKS_URL=http://api-gateway:8080/.well-known/jwks.json`; production should prefer a pinned public key
or a trusted JWKS endpoint.

Each live connection is registered in local memory and in Redis with a TTL. Redis
tracks user-to-device membership, device-to-connection ownership, connection identity,
gateway membership, and cross-gateway route notifications. Socket objects never go to
PostgreSQL.

## Protocol

Send a message through the durable API path:

```json
{"type":"message.send","requestId":"req-1","chatId":"chat-uuid","clientMessageId":"client-uuid","payload":{"type":"text","body":"hello"}}
```

The gateway calls the API gateway with the bearer token and idempotency key. Only after
the API persists the message does it return:

```json
{"type":"message.persisted","requestId":"req-1","messageId":"message-uuid","chatId":"chat-uuid","sequence":123}
```

Supported server event types are `message.new`, `message.delivered`, `message.read`,
`typing.start`, `typing.stop`, `presence.updated`, `sync.delta`, `heartbeat.ack`, and
`error`. Clients may send `heartbeat` and must send a `sync.resume` after reconnect:

```json
{"type":"sync.resume","requestId":"resume-1","deviceId":"device-uuid","globalSyncCursor":"g-7","perChatCursors":{"chat-uuid":"122"},"lastAcknowledgedClientMessageId":"client-uuid"}
```

The gateway reads each per-chat delta from the API's PostgreSQL-backed message
history (`after` cursor) and returns `sync.delta`. It does not reconstruct history
from process memory or Redis.

The server sends WebSocket ping frames approximately every 30 seconds and expects a
pong within 90 seconds. Clients should send the application-level `heartbeat` roughly
every 20–60 seconds. Outbound queues are bounded; a full queue increments the
backpressure metric and closes the connection with code 1013.

Run locally with:

```bash
go run ./cmd/ws-gateway
```

The default port is `8081`; configure it with `PORT`.

Useful configuration includes `REDIS_ADDR`, `REDIS_PASSWORD`, `API_BASE_URL`,
`HEARTBEAT_INTERVAL`, `HEARTBEAT_TIMEOUT`, `SEND_QUEUE_SIZE`, and `MAX_MESSAGE_BYTES`.
Prometheus-compatible metrics are exposed at `/metrics`, including connection count,
reconnects, heartbeat misses, backpressure, and message routing counters.
