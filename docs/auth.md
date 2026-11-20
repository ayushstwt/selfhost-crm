# Authentication

Selfhost CRM has two entry points with two security chains: a server-rendered **web UI** (session cookie + CSRF) and a **JSON API** under `/api/**` (stateless JWT bearer tokens). Both share the same users, password rules, lockout and audit log.

Contents: [First-run setup](#first-run-setup) · [Web login and CSRF with HTMX](#web-login-and-csrf-with-htmx) · [API flow](#api-flow) · [Token lifetimes](#token-lifetimes) · [Account rules and lockout](#account-rules-and-lockout) · [Error codes](#error-codes) · [curl examples](#curl-examples) · [Operations notes](#operations-notes) · [Known limitations](#known-limitations)

## First-run setup

- With **zero users**, every web route redirects to `/setup`, where the first Admin is created (display name, email, password, confirm). `/actuator/**` and `/api/**` are not redirected.
- As soon as any user exists, `/setup` returns **404 permanently** (also for soft-deleted users: once a user has existed, setup stays closed).
- Race safety: `SetupService.createFirstAdmin` runs in one transaction that first takes `LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE`. Concurrent requests queue up; the second one sees the committed admin and gets 404. A test fires 8 parallel setups and asserts exactly one admin.
- Writes a `SETUP_COMPLETED` audit entry. There are no default or hardcoded credentials anywhere.

## Web login and CSRF with HTMX

- `GET /login` renders the form; `POST /login` (fields `username` = email, `password`) is handled by Spring Security. `POST /logout` ends the session.
- Session cookie `JSESSIONID`: `HttpOnly`, `SameSite=Lax`, `Secure` when `SESSION_COOKIE_SECURE=true` (set this behind HTTPS). The session id is changed on login (session fixation protection).
- **CSRF is on** for all web POSTs. Thymeleaf forms get the hidden `_csrf` field automatically. Every page sets the token for HTMX on the body:

  ```html
  <body hx-headers='{"X-CSRF-TOKEN": "<token>"}'>
  ```

  so any `hx-post`, `hx-put` or `hx-delete` request carries the header without extra work. New pages should copy the `th:attr="hx-headers=..."` expression from `templates/login.html`.
- A wrong password, an unknown email and a locked account all redirect to `/login?error` with the same message. A disabled account is only reported (`/login?disabled`) after the correct password.
- Live sessions are re-checked on every request (`ActiveUserSessionFilter`): a disabled or deleted user is logged out immediately, and so is every session that authenticated **before** the last password change or the last access change (role, enabled or deleted state). The session that performs a password change stays logged in. A surviving session gets its authentication rebuilt from the database each request, so roles and permissions are never stale.
- `/invite/{token}` is public (see [rbac.md](rbac.md)); the setup redirect does not apply to it.

## API flow

All `/api/**` responses use the `ApiResponse` envelope. **Branch on the HTTP status**, not on the body's `status` field.

| Endpoint | Auth | Result |
|----------|------|--------|
| `POST /api/auth/login` | public | 200 with `accessToken`, `refreshToken`, `tokenType` (`Bearer`), `expiresIn` (seconds) |
| `POST /api/auth/refresh` | public (refresh token in body) | 200 with a new token pair; the old refresh token is revoked |
| `POST /api/auth/logout` | public (refresh token in body) | 204, no body, always (see below) |
| `GET /api/auth/me` | bearer | 200 `UserResponse` |
| `POST /api/auth/change-password` | bearer | 200; revokes **all** refresh tokens of the user |
| `GET /api/users/me/activity?page=0&size=20` | bearer | 200, the caller's own audit entries, newest first, with `metadata` (`size` max 100) |

User management, invitations and the admin audit log are documented in [rbac.md](rbac.md). Public invitation endpoint: `POST /api/invitations/accept`.

Everything else under `/api/**` is **deny by default**: an endpoint that has no explicit rule in `ApiSecurityConfig` is reachable by ADMIN only (a test asserts MEMBER and MANAGER get 403 on an unlisted route).

**Access tokens** are HS256 JWTs verified by Spring Security's OAuth2 resource server (Nimbus). Claims: `sub` = user id, `email`, `role`, `iat`, `exp`, `jti`. The token only *identifies*; on every request the user is loaded from the database (one query) and the principal (`UserPrincipal`: id, role, derived authorities) comes from that row, not from the token:

- a token of a disabled or deleted user is rejected at once;
- authorities are `ROLE_<role>` plus one authority per permission of that role (`RolePermissions`), so a role edited in the database takes effect on the next request;
- a token whose `iat` is before `users.access_changed_at` is rejected. That column is set when the role changes or the user is disabled, enabled or deleted. JWT `iat` has whole-second precision, so the change stores the *next* whole second: every token minted up to and including the second of the change dies, and a re-login inside that same second is rejected too and has to be repeated.

**Refresh tokens** are opaque: 256 random bits, URL-safe (43 characters). Only the SHA-256 hash is stored; the raw value is returned once and never logged. They rotate on every use:

1. `refresh` issues a new token in the same family and marks the old one revoked (`revoked_at`, `replaced_by_id`).
2. Presenting an already-revoked token again means it was probably stolen: the **whole family is revoked** and `REFRESH_REUSE_DETECTED` is written to the audit log.
3. Unknown, expired and reused tokens all return the same generic `401` (`E010`). So does a token whose user was soft-deleted (the locked lookup does not return tokens of deleted users; this used to surface as a 500).
4. Role changes, disabling, enabling and deleting a user revoke all of that user's refresh tokens.

**Logout** is public because the refresh token in the body is the credential. It revokes that token, returns `204` with an empty body for any well-formed token, and never reveals whether the token existed, was expired or was already revoked. A malformed token gets `400`. A stale `Authorization` header on the public endpoints is ignored.

## Token lifetimes

| Setting | Env var | Default |
|---------|---------|---------|
| Access token | `JWT_ACCESS_TOKEN_TTL` | `15m` |
| Refresh token | `JWT_REFRESH_TOKEN_TTL` | `7d` |
| Web session | `server.servlet.session.timeout` | `30m` idle |
| Expired-token cleanup | `REFRESH_TOKEN_CLEANUP_ENABLED`, `REFRESH_TOKEN_CLEANUP_INTERVAL` | on in prod, every `1h` |

`JWT_SECRET` (HS256, **at least 32 bytes**, for example `openssl rand -base64 48`):

- prod profile: startup fails if it is missing or too short;
- dev profile: if unset, a random secret is generated at startup and a warning says API tokens stop working after a restart; if set but too short, startup still fails;
- no secret is ever stored in the repository.

## Account rules and lockout

- Password: **12 to 128 characters**, must not equal the email (case-insensitive). Stored with BCrypt over a SHA-256 pre-hash, so all 128 characters count (plain BCrypt only reads 72 bytes).
- Emails are stored lowercase and are unique **among live users** (partial unique index on `email where is_deleted = false`), so the email of a deleted user can be used again.
- Lockout: after `LOGIN_MAX_ATTEMPTS` (default 5) consecutive wrong passwords the account is locked for `LOGIN_LOCKOUT_DURATION` (default 15m). The right password does not unlock it early. Failures while locked do not extend the lock. A successful login resets the counter.
- A locked account returns the **same generic 401 `E006`** as a wrong password on the API (and the same redirect on the web). The lockout is logged server-side only. The password is always verified first, so response time is the same for every case.
- A disabled user (`enabled = false`) cannot log in, cannot use access tokens (rejected per request) and cannot refresh.
- Changing the password (web or API) needs the current password, revokes all refresh tokens, and ends the user's other web sessions.
- Audit log (`user_log`): `LOGIN`, `LOGOUT`, `PASSWORD_CHANGED`, `REFRESH_REUSE_DETECTED`, `SETUP_COMPLETED`, plus the administrative actions `USER_UPDATED`, `USER_ROLE_CHANGED`, `USER_DISABLED`, `USER_ENABLED`, `USER_DELETED`, `INVITATION_CREATED`, `INVITATION_REVOKED`, `INVITATION_ACCEPTED`, each `SUCCESS` or `FAILURE`. Administrative entries carry the actor (`user_id`) and the target (`target_type` USER or INVITATION, `target_id`) and are written in the same transaction as the change; refused actions are not logged. The database has CHECK constraints for `action`, `sub_action` and `target_type` that mirror the Java enums (adding an action needs a migration; a test fails on drift). Failed logins for emails that do not exist are **not** written to the table; they go to the application log with a short hash fingerprint instead of the email. Passwords, tokens and request bodies are never logged.

## Error codes

The code is the `ErrorMessages` constant name; the HTTP status comes from the same constant.

| Code | HTTP | Meaning |
|------|------|---------|
| `E001` | 404 | Resource not found (also unknown API routes) |
| `E002` | 409 | Duplicate email |
| `E003` | 401 | Authentication required (missing, malformed, expired or tampered token) |
| `E004` | 403 | Access denied |
| `E005` | 400 | Validation failed (`errors[]` has one entry per field) |
| `E006` | 401 | Invalid email or password (also returned for a locked account) |
| `E007` | 403 | Account disabled (only after the correct password) |
| `E008` | 404 | Setup already completed |
| `E009` | 429 | Too many attempts. Reserved; login never returns it, to avoid revealing locked accounts |
| `E010` | 401 | Invalid or expired refresh token (also reuse) |
| `E011` | 400 | Password must not be the same as the email |
| `E012` | 400 | Current password is incorrect |
| `E013` | 403 | Not allowed on this user (target-based rule, for example a Manager acting on an Admin) |
| `E014` | 409 | The last enabled Admin cannot be demoted, disabled or deleted |
| `E015` | 403 | You cannot change the role, enabled state or existence of your own account |
| `E016` | 400 | Invitation link invalid, expired, used or revoked (one code for all four) |
| `E017` | 409 | A live invitation for this email was created by someone else (Manager only) |
| `E400` | 400 | Bad request (unreadable JSON, missing parameter, type mismatch) |
| `E405` | 405 | Method not allowed |
| `E415` | 415 | Unsupported media type |
| `E500` | 500 | Unexpected error. Generic message; details only in the server log |

Error body:

```json
{
  "status": "error",
  "message": "Validation failed",
  "errorCode": "E005",
  "data": null,
  "errors": [{ "field": "email", "message": "Email is required" }],
  "path": "/api/auth/login",
  "timestamp": "2026-11-17T10:15:30Z"
}
```

## curl examples

```bash
B=http://localhost:8080

# login
curl -s $B/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"your-password-here"}'

# current user
curl -s $B/api/auth/me -H "Authorization: Bearer $ACCESS"

# my activity (page 0, 10 per page)
curl -s "$B/api/users/me/activity?page=0&size=10" -H "Authorization: Bearer $ACCESS"

# rotate the refresh token (the old one stops working)
curl -s $B/api/auth/refresh -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$REFRESH\"}"

# change password (revokes all refresh tokens)
curl -s $B/api/auth/change-password -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"currentPassword":"your-password-here","newPassword":"a-new-password-12+"}'

# logout (public; the refresh token is the credential) -> 204
curl -si $B/api/auth/logout -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$REFRESH\"}"

# no token -> 401 in the envelope
curl -si $B/api/auth/me
```

## Operations notes

- **Rate limiting per IP belongs at the reverse proxy** (nginx, Caddy, Traefik, a cloud load balancer). The application only throttles per account. Put limits on `POST /api/auth/login`, `/api/auth/refresh` and `/login`.
- Terminate TLS at the proxy and set `SESSION_COOKIE_SECURE=true`.
- API docs (`/swagger-ui.html`, `/v3/api-docs`) exist in the dev profile for signed-in web users and are disabled in prod.
- Rotate `JWT_SECRET` to invalidate all access tokens at once (refresh tokens live in the database and are unaffected).

## Known limitations

- **Parallel refreshes revoke the token family.** Two simultaneous refresh requests with the same token are serialized by a row lock; the first wins and the second looks like a reuse, so the whole family is revoked and the client must sign in again. Clients should serialize their refresh calls (one in flight at a time).
- Access tokens stay valid until they expire (15 minutes by default) after a password change; only refresh tokens and web sessions are cut off. Disabling, enabling, deleting a user and changing a role take effect immediately (see `access_changed_at` above).
- **Soft-delete filters on append-only tables.** `User` carries `@SQLRestriction("is_deleted = false")`. `UserLog`, `RefreshToken` and `Invitation` deliberately do not: they are never soft-deleted (the log is append-only, refresh tokens are hard-deleted by the cleanup job, invitations end through `accepted_at` / `revoked_at`), so a filter would only hide rows. Consequence: their `user` references can point at a soft-deleted user. Code that touches such a reference must not load the lazy association: audit and invitation reads use DTO projections with a LEFT JOIN (name is `null` for a deleted user), invitations store plain ids, and the refresh lookup excludes tokens of deleted users.- Each API request costs one database lookup (this is what makes disabling immediate).
- No per-IP rate limiting in the application (see operations notes).
- No self-service registration, password reset by email, MFA or SSO yet.
- `E009` is defined but unused on purpose.
