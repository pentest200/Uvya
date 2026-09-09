# Shared packages

Shared packages are reserved for versioned, dependency-light contracts and generated types. Business logic belongs in the owning service, not in a shared package.

- `contracts` will contain versioned HTTP/event schemas.
- `frontend-types` will contain frontend-safe types generated from or aligned with published contracts.
