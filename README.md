# Uvya

Uvya is a production-oriented, Telegram-like real-time messaging platform being built incrementally. This repository currently contains the engineering foundation only; no authentication, chat, message, or other business feature has been implemented yet.

## Foundation milestone

This milestone provides:

- a clean monorepo layout and contribution conventions;
- a minimal Next.js web shell;
- a Spring Boot HTTP API gateway with liveness/readiness endpoints and request ID propagation;
- a Go WebSocket gateway process with liveness/readiness endpoints and graceful shutdown;
- local PostgreSQL, Redis, Kafka in single-node KRaft mode, and MinIO;
- architecture documentation and ADRs for the initial system shape and MVP message-storage choice;
- Dockerfiles, Compose orchestration, and GitHub Actions checks.

Future domain services are documented boundaries, not empty placeholder applications. They will be added with their first real contract and tests.

## Prerequisites

The supported clean-machine path requires:

- Docker Desktop with Compose v2;
- Git;
- 8 GB RAM available to Docker for the full local stack;
- ports `3000`, `5432`, `6379`, `8080`, `8081`, `9000`, `9001`, and `9092` available.

Native Maven, Go, and Node installations are optional for the Docker-based path. They are required only for the corresponding `make test` and `make lint` targets when run directly on the host.

## Start locally

From the repository root, create the ignored local environment file and start the stack:

PowerShell:

```powershell
Copy-Item .env.example .env
docker compose up --build -d
docker compose ps
```

Bash:

```bash
cp .env.example .env
docker compose up --build -d
docker compose ps
```

The first start downloads pinned images and builds the three application containers, so it can take several minutes. Kafka uses KRaft and therefore does not start Zookeeper.

## Verify locally

PowerShell:

```powershell
Invoke-WebRequest http://localhost:8080/health/live | Select-Object -ExpandProperty Content
Invoke-WebRequest http://localhost:8080/health/ready | Select-Object -ExpandProperty Content
Invoke-WebRequest http://localhost:8081/health/live | Select-Object -ExpandProperty Content
Invoke-WebRequest http://localhost:8081/health/ready | Select-Object -ExpandProperty Content
Invoke-WebRequest http://localhost:3000 | Select-Object -ExpandProperty StatusCode
Invoke-WebRequest http://localhost:9000/minio/health/live | Select-Object -ExpandProperty StatusCode
docker compose ps
```

Bash:

```bash
curl --fail http://localhost:8080/health/live
curl --fail http://localhost:8080/health/ready
curl --fail http://localhost:8081/health/live
curl --fail http://localhost:8081/health/ready
curl --fail http://localhost:3000
curl --fail http://localhost:9000/minio/health/live
docker compose ps
```

Expected health responses include `"status":"UP"` and an `X-Request-ID` response header. The web shell is intentionally informational until a product contract is introduced.

## Useful commands

```bash
docker compose logs -f
docker compose logs -f api-gateway ws-gateway
docker compose restart
docker compose down
docker compose config --quiet
```

The local volumes are retained by `docker compose down`. To deliberately remove local database, Redis, Kafka, and MinIO data, run `docker compose down -v`.

The Makefile provides equivalent lifecycle commands where `make` is available:

```bash
make up
make ps
make config
make down
```

## Repository layout

```text
apps/web                 Next.js web shell
services/api-gateway     Spring Boot HTTP edge process
services/ws-gateway      Go WebSocket edge process
packages/contracts       Versioned API/event contract home
packages/frontend-types  Frontend-safe generated type home
infrastructure           Local and future deployment documentation
tests                    Integration, load, and security test homes
docs/architecture        System and engineering conventions
docs/adr                 Architecture decision records
.github/workflows        Continuous integration
```

## Architecture

```mermaid
flowchart LR
    Browser[Browser] --> Web[Next.js web shell]
    Web -->|HTTP / X-Request-ID| API[Spring Boot API gateway\n:8080]
    Web -.->|Future WebSocket protocol| WS[Go WebSocket gateway\n:8081]

    API -.->|Future owned APIs| Domain[Domain services]
    WS -.->|Future durable event consumption| Kafka[(Kafka\nKRaft)]
    API -.->|Future durable writes / outbox| Postgres[(PostgreSQL)]
    API -.->|Future ephemeral state| Redis[(Redis\nTTL state)]
    API -.->|Future object storage| MinIO[(MinIO\nS3-compatible)]

    Domain -.-> Postgres
    Domain -.-> Kafka
    WS -.-> Redis
```

Solid edges represent the currently runnable entry points. Dashed edges are deliberate future integration boundaries; no business feature is implied by this foundation.

## Engineering conventions

- [Foundation architecture](docs/architecture/foundation.md)
- [Engineering conventions](docs/architecture/engineering-conventions.md)
- [ADR-001: Initial architecture](docs/adr/ADR-001-initial-architecture.md)
- [ADR-002: MVP message storage](docs/adr/ADR-002-postgresql-for-mvp-message-storage.md)
- [Contributing](CONTRIBUTING.md)

## Current scope and next milestone

There are no database migrations in this milestone because no domain schema exists yet. The next logical milestone is authentication and device/session management, including its API contract, PostgreSQL migrations, security tests, and outbox decisions.
