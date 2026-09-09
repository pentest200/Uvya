# Engineering conventions

## Configuration

- Configuration is read from environment variables and bound by the owning process.
- `.env.example` documents local values; `.env` is ignored and must not contain production credentials.
- Names are uppercase with underscores, for example `SERVER_PORT` and `REDIS_PASSWORD`.
- Required secrets fail fast at Compose configuration time or application startup.
- Defaults are limited to safe local development values and must not be treated as production defaults.
- Timeouts, retry limits, and pool sizes must be explicit once an external client is added.

## Health and readiness

Every service exposes separate liveness and readiness endpoints:

- `GET /health/live` answers whether the process is alive and able to serve a probe.
- `GET /health/ready` answers whether the process is accepting traffic for its current responsibilities.

Readiness must include required dependency checks when the service begins using a dependency. A dependency outage must not turn a liveness failure into a restart loop.

## Request and correlation IDs

- Clients may send `X-Request-ID` with a safe value up to 128 characters.
- A service preserves a valid incoming ID or creates a cryptographically random replacement.
- The selected ID is returned in the response header and placed in structured log context.
- Cross-service calls must forward the ID. Distributed tracing can later add W3C `traceparent` without removing this operational identifier.
- IDs are metadata only and must never include user content or credentials.

## Logging

- Emit one structured JSON event per log entry on stdout.
- Include timestamp, severity, service name, message, and request ID when available.
- Log lifecycle, validation, dependency, and retry events with stable event names.
- Do not log message bodies, access tokens, passwords, cookies, encryption keys, or uploaded file contents.
- Use metrics and trace attributes for aggregate diagnostics instead of copying private payloads.

## API and event contracts

- Version public HTTP routes under `/api/v1` once a business route exists.
- Use explicit request/response schemas and pagination for unbounded collections.
- Durable events include `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `producer`, and `correlationId`.
- Schema changes are backward-compatible where possible and are reviewed as architecture changes when they alter ownership or delivery guarantees.

## Database changes

- Production schema changes use Flyway migrations owned by the service that owns the tables.
- Add indexes based on access patterns, not speculation.
- Transactions cover the state change and its outbox record when an event must be published.
- Use optimistic concurrency or an equivalent explicit versioning strategy where concurrent updates can conflict.
