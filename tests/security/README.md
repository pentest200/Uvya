# Security tests

The API gateway security suite lives under `services/api-gateway/src/test`.

It covers:

- generic invalid-credential responses for known and unknown accounts;
- BCrypt password verification and one-way refresh-token hashing;
- account lockout after repeated failures;
- refresh rotation and replay-lineage revocation;
- expired and malformed JWT rejection;
- active-session revocation and horizontal session ownership checks;
- CSRF-protected cookie refresh/logout transport and request validation.

Run it with:

```bash
cd services/api-gateway
mvn -B test
```
