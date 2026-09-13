# Services

Only platform entry points are bootstrapped in the foundation milestone:

| Service | Status | Responsibility in this milestone |
| --- | --- | --- |
| `api-gateway` | Active foundation | Authentication, chats, durable messages, transactional outbox, Kafka events, and health/readiness endpoints |
| `ws-gateway` | Active realtime edge | Authenticated WebSockets, Redis connection metadata/routing, durable message delegation, resume sync, heartbeat, backpressure, and metrics |

The future `auth-service`, `user-service`, `chat-service`, `message-service`, `notification-service`, `media-service`, `search-service`, and `moderation-service` are architectural boundaries, not placeholder applications. They will be added only with a concrete milestone, data ownership, API contract, and tests.
