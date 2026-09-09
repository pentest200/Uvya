# Infrastructure

Local infrastructure is defined in the root `docker-compose.yml` so a developer can start the entire foundation with one command. Provider-specific deployment assets are intentionally deferred until service contracts and operational requirements stabilize.

- `docker/` — local container documentation.
- `k8s/` — Kubernetes deployment notes; manifests are not yet introduced.
- `helm/` — Helm packaging notes; charts are not yet introduced.
- `terraform/` — infrastructure-as-code notes; cloud resources are not yet introduced.
- `monitoring/` — observability deployment notes; service instrumentation conventions are documented first.
