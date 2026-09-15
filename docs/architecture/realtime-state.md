# Ephemeral realtime state

The Go WebSocket gateway owns connection-local socket objects. Redis holds only
short-lived coordination state:

| State | Redis key | Lifetime |
| --- | --- | --- |
| User presence | `uvya:ws:presence:{userId}` | `PRESENCE_TTL`, refreshed by heartbeat |
| Typing indicator | `uvya:ws:typing:{chatId}:{userId}` | `TYPING_TTL` |
| Device connection | `uvya:ws:device:{deviceId}:connection` | connection metadata TTL |
| Presence watchers | `uvya:ws:presence:watchers:{userId}` | subscription TTL, refreshed by heartbeat |

Presence contains online/offline status, invisible mode, the latest device activity,
device ID, and last-seen time. A disconnect marks a user offline only after the
last active device is gone. Typing is never stored in PostgreSQL; its event includes
`expiresAt` so clients can expire the indicator even if a gateway disappears before
it sends `typing.stop`.

Presence updates are not sent to every contact. A client sends
`presence.subscribe` with a chat ID and user IDs. The gateway asks the durable API
for active chat membership, records each subscriber device in Redis, and sends
updates only to those device connections. Presence state changes are debounced;
heartbeat refreshes extend TTLs without generating a broadcast.

Redis is an optimization and coordination dependency for this state, not the source
of truth for messages. If Redis is unavailable, authenticated local sockets can
still persist and synchronize messages through the API. Presence and typing return
degraded errors, while durable API authorization remains the fallback for chat
membership checks. Offline messages remain in durable message/read state and are
recovered with cursor-based synchronization rather than an unbounded Redis queue.
