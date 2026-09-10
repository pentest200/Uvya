# Contracts

This package contains the shared, versioned event schemas used by the Uvya data foundation.
The first envelope and payload schemas live in [events/v1](events/v1). They are transport
neutral and can be consumed by Java, TypeScript, Go, or a future Kafka adapter.

Contract rules:

- Use additive, backward-compatible changes where possible.
- Include an explicit schema version in durable event envelopes.
- Include `eventId`, `eventType`, `eventVersion`, `occurredAt`, `traceId`, and
  `idempotencyKey` in durable events.
- Never put private message bodies in logs or telemetry exemplars.
