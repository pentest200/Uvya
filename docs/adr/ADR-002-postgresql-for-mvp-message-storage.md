# ADR-002: Use PostgreSQL for MVP message storage and defer ScyllaDB

- Status: Accepted
- Date: 2026-09-10
- Owners: Uvya engineering

## Context

Messages are durable user data. The platform needs idempotent writes, per-chat ordering, read/delivery state, replies, edits, deletes, reactions, moderation references, and auditable state transitions. The MVP must remain locally runnable and testable while traffic patterns are still unknown.

ScyllaDB may become useful when message volume, partition sizes, write throughput, geographic distribution, or retention patterns demonstrate a need for a distributed wide-column store. Introducing it before those measurements would add a second durable message system and force premature consistency and migration decisions.

## Decision

Use PostgreSQL as the initial message system of record when the message service is implemented. Model the message aggregate and its supporting indexes around the access patterns that the first product contract proves. Use Flyway migrations, explicit indexes, transaction boundaries, optimistic concurrency where needed, and a transactional outbox for important Kafka events.

Defer ScyllaDB until measured requirements justify it. If it is introduced later, use an explicit migration/dual-write or backfill plan with a defined source of truth and reconciliation process; do not silently split authority between PostgreSQL and ScyllaDB.

## Why PostgreSQL fits the MVP

- It is easy to run locally and in Testcontainers.
- Transactions cover message state and outbox records in one commit.
- Unique constraints support `clientMessageId` idempotency.
- Relational constraints make membership, authorization, replies, and moderation references easier to validate.
- Querying, migrations, backups, and operational expertise are widely available.
- It supports a measured path to partitioning and read replicas before a storage-engine change is necessary.

## Why ScyllaDB is deferred

- Its operational model and data modeling are optimized for scale characteristics not yet measured.
- It would add another local and CI dependency before a proven need exists.
- Cross-aggregate transactional workflows and flexible MVP queries would require additional application coordination.
- A premature migration could lock the product into partition-key and denormalization choices before retention and fan-out behavior are understood.

## Consequences

The MVP prioritizes correctness and iteration speed over maximum write scale. PostgreSQL capacity, indexing, partitioning, and query performance must be observed as real workloads arrive. The architecture preserves a future seam through service ownership and event contracts, but it does not claim that moving to ScyllaDB will be automatic.

## Revisit triggers

Re-evaluate this decision when measured workloads show sustained PostgreSQL write or storage pressure after query/index tuning, partitioning, connection management, and read-scaling work; when partition sizes exceed operational targets; or when multi-region availability requirements cannot be met acceptably with PostgreSQL architecture.
