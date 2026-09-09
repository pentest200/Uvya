# ADR-001: Initial service-oriented monorepo architecture

- Status: Accepted
- Date: 2026-09-10
- Owners: Uvya engineering

## Context

Uvya must start as a locally runnable product while preserving a path to horizontally scaled realtime delivery. The system will eventually need identity, conversations, durable messages, media, search, moderation, notifications, and WebSocket fan-out. Building every service and production deployment layer before a first product contract would make the repository difficult to validate and operate.

## Decision

Use a monorepo with independently buildable services and shared contract packages:

- Next.js and TypeScript for the web client;
- Spring Boot and Java 21 for HTTP/domain services where relational transactions and mature security integrations are valuable;
- Go for the WebSocket gateway where a small, efficient connection process is appropriate;
- PostgreSQL as the initial relational system of record;
- Redis for TTL-backed ephemeral state and connection routing;
- Kafka for durable asynchronous events;
- MinIO locally as an S3-compatible media store;
- Docker Compose for the local runtime foundation;
- Kubernetes/Helm/Terraform and production observability assets only after the contracts they deploy are established.

The first implementation contains the web shell, an API gateway with the authentication foundation, and a WebSocket gateway lifecycle foundation. Domain services are not scaffolded as empty applications.

## Consequences

### Positive

- A clean-machine developer path exists without requiring native Java, Go, or Node installations.
- WebSocket process memory is clearly separated from durable state.
- Service ownership and contract boundaries are visible before feature work begins.
- The stack can evolve from one local process per entry point to independently scaled components.

### Negative

- The repository contains more operational documentation before it contains product behavior.
- Multiple language toolchains increase CI and maintenance surface.
- Local Kafka and object storage consume meaningful developer resources.
- Some target boundaries remain intentionally unimplemented until they have real behavior.

## Rejected alternatives

- A single all-in-one backend: simpler initially, but it would obscure the realtime/durable boundary and make later extraction harder.
- A full microservice fleet from day one: adds operational complexity without validating product requirements.
- Redis as the offline message store: rejected because TTL/cache semantics do not provide the required durable message guarantees.

## Follow-up

Authentication and device/session ownership are now implemented in the API gateway. The next milestone should establish the first domain contract and its event publication needs.
