# Docker

The canonical local stack is defined at the repository root in [`docker-compose.yml`](../../docker-compose.yml). It runs PostgreSQL, Redis, a single-node Kafka broker in KRaft mode, MinIO, the two bootstrapped edge services, and the web shell.

The Kafka configuration has no Zookeeper dependency. The local broker is intentionally single-node and plaintext; production security, replication, and topology belong in a later deployment milestone.
