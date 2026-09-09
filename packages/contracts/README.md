# Contracts

This package is intentionally documentation-only in the foundation milestone. Future API and event schemas will be versioned here after the first owning service and persistence model exist.

Contract rules:

- Use additive, backward-compatible changes where possible.
- Include an explicit schema version in durable event envelopes.
- Include `eventId`, `occurredAt`, `producer`, and `correlationId` in durable events.
- Never put private message bodies in logs or telemetry exemplars.
