# Services

Only platform entry points are bootstrapped in the foundation milestone:

| Service | Status | Responsibility in this milestone |
| --- | --- | --- |
| `api-gateway` | Bootstrapped | HTTP process lifecycle, request IDs, and health/readiness endpoints |
| `ws-gateway` | Bootstrapped | WebSocket edge process lifecycle, request IDs, and health/readiness endpoints |

The future `auth-service`, `user-service`, `chat-service`, `message-service`, `notification-service`, `media-service`, `search-service`, and `moderation-service` are architectural boundaries, not placeholder applications. They will be added only with a concrete milestone, data ownership, API contract, and tests.
