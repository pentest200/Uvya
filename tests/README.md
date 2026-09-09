# Tests

Test suites are grouped by operational concern:

- `integration/` — cross-process and dependency-backed tests.
- `load/` — k6 scenarios and performance thresholds.
- `security/` — authentication, authorization, abuse, and dependency hardening checks.

The foundation milestone keeps service-local unit tests beside their owning service.
