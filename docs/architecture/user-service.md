# Uvya User Service

The User Service owns profile presentation data and privacy-aware contact matching.
It reuses the Phase 2 `app_users` account table and the Phase 1 `blocked_users` table;
it does not create a parallel identity or account model.

## Profile data

`user_profiles` is keyed by the Phase 2 account UUID. It stores the username and its
case-folded unique form, display name, bio, avatar metadata, privacy settings, and
discoverability. Account status remains authoritative in `app_users`, so disabled or
locked accounts cannot be discovered through public search.

`GET /v1/users/me` returns the authenticated account's email and private settings.
Public profile responses never include email, privacy settings, phone numbers, contact
identifiers, password hashes, or tokens. A profile's discoverability is one of:

- `PUBLIC`: eligible for public lookup and search;
- `CONTACTS_ONLY`: visible only when the viewer has uploaded a matching identifier;
- `NOBODY`: hidden from other users.

Blocked users are hidden in both directions and return the same not-found response as a
private or undiscoverable profile. This avoids exposing the existence of a blocked
relationship.

## Contact matching

`POST /v1/users/contacts` accepts email or phone identifiers. Email values are trimmed
and case-folded. Phone values are reduced to an international `+`-prefixed digit form.
Only SHA-256 digests are stored in `contact_identifiers`; raw phone values are never
persisted, logged, or returned. Existing account email remains in `app_users` because
it is needed by authentication, but it is not exposed by contact-match responses.

Matching requires an active target account and respects blocking, `NOBODY`, and the
`privacy_settings.allowContactMatching` flag. A match is marked mutual only when the
target has uploaded the owner's same normalized identifier. Contact upload is audited
without including raw identifiers.

## Cache and invalidation

Only `PUBLIC` response projections are cached in Redis under
`uvya:user-profile:v1:{userId}` with a five-minute TTL. Cache failures are fail-open:
the database remains authoritative. Profile updates and block/unblock operations evict
the affected public-profile key. Private `/me` reads bypass the cache, and cache values
never contain email or privacy settings.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/v1/users/me` | Read the authenticated profile |
| PATCH | `/v1/users/me` | Update only the authenticated profile |
| GET | `/v1/users/{userId}` | Privacy-filtered public profile |
| GET | `/v1/users/search` | Paginated public username/display-name search |
| POST | `/v1/users/contacts` | Upload normalized contact identifiers and receive matches |
| POST | `/v1/users/{userId}/block` | Block another account |
| DELETE | `/v1/users/{userId}/block` | Remove a block |

All endpoints require the Phase 2 bearer access token. Mutating bearer-token endpoints
are explicitly exempted from cookie CSRF checks; refresh/logout continue using the
existing HttpOnly refresh-cookie plus CSRF strategy.
