# Uvya authentication foundation

The API gateway owns the initial authentication boundary. It intentionally keeps authentication state in PostgreSQL and uses Redis only for short-lived, high-churn control state.

## Token transport

- Access tokens are signed JWTs with a 15-minute default lifetime. Clients send them as `Authorization: Bearer <token>`.
- Refresh tokens are 256-bit opaque values. Only an HMAC-SHA-256 digest, keyed by `UVYA_REFRESH_TOKEN_PEPPER`, is stored in `auth_sessions`.
- Browser clients receive the refresh token in an `HttpOnly`, `SameSite=Strict` cookie scoped to `/v1/auth`. Set `UVYA_REFRESH_COOKIE_SECURE=true` behind HTTPS.
- Native clients may submit a refresh token in the refresh request body; the response still rotates it and sets the cookie.
- The old refresh session is retained as revoked with a replacement reference. Reuse of an old token revokes the replacement and device, which makes replay visible and stops the lineage.

## Account, device, and session ownership

An account can have many devices, and each device can have many sessions. A session is the unit represented by a refresh token. Session and device operations always query by both resource ID and authenticated user ID. A revoked session is checked at the resource-server filter, so revocation invalidates its still-unexpired access token as well.

Registration and password login create or update a device using the supplied device ID when that device already belongs to the account. An authenticated `POST /v1/auth/devices` endpoint provides explicit device registration for clients that want to register before a later login.

## Risk controls

Redis keys for login limits and OTP challenges contain SHA-256 identifiers rather than raw email addresses or IP strings. Login attempts are limited by IP and normalized account identifier. PostgreSQL tracks consecutive password failures and applies an account lock. Login failure responses intentionally use the same message for unknown and known accounts.

`RedisOtpStateStore` is the email/OTP seam: future verification flows can store only a hashed code with a TTL and consume it without changing account/session persistence.

## CSRF and CORS

Because browser refresh/logout use a cookie, CSRF protection remains enabled for those state-changing endpoints. `GET /v1/auth/csrf` creates a readable `XSRF-TOKEN` cookie and returns the matching header name/token; browser clients send `X-XSRF-TOKEN` on refresh/logout. Registration, login, and JWT-only device registration do not require a CSRF token. CORS allows only configured origins and credentials.

## Audit and logging

Authentication outcomes are persisted in `auth_audit_logs` and emit structured events containing only event type and non-secret identifiers. Passwords, access tokens, refresh tokens, token hashes, and OTP values are never logged or returned as diagnostic data.

## Endpoints

| Method | Path | Authentication |
| --- | --- | --- |
| POST | `/v1/auth/register` | Public |
| POST | `/v1/auth/login` | Public |
| POST | `/v1/auth/refresh` | CSRF for cookie/browser transport |
| POST | `/v1/auth/logout` | CSRF for cookie/browser transport |
| GET | `/v1/auth/csrf` | Public |
| GET | `/v1/auth/sessions` | Access JWT |
| DELETE | `/v1/auth/sessions/{sessionId}` | Access JWT + ownership check |
| POST | `/v1/auth/devices` | Access JWT |
