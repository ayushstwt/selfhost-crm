# Selfhost CRM

Open-source, self-hosted CRM with built-in AI. Your server, your data.

> **Status: early development.** Not production ready.

## Tech stack
Spring Boot 4.1, Java 21, PostgreSQL, Flyway, Spring Data JPA, Spring Security, Thymeleaf + HTMX, Docker Compose. AI (Spring AI + Groq) is planned.

One install = one organization (single tenant). Roles: Admin, Manager, Member, plus teams.

## Quick start
```bash
git clone https://github.com/ayushstwt/selfhost-crm.git
cd selfhost-crm
cp .env.example .env            # then set DB_PASSWORD (12+ chars), JWT_SECRET and APP_BASE_URL
docker compose --profile app up -d --build
# open http://localhost:8080 (the first visit asks you to create the administrator)
```

`prod` is the default Spring profile: the app refuses to start without a strong `DB_PASSWORD` (at least 12 characters, not `change-me`, `selfhostcrm`, `postgres` or `password`) a `JWT_SECRET` and an `APP_BASE_URL` (absolute http(s) URL). PostgreSQL is published on `127.0.0.1` only. Full guide (reverse proxy, backup, upgrade): [docs/deploy.md](docs/deploy.md).

### Local development
`docker compose up -d` without `--profile app` starts only PostgreSQL. Run the app with the dev profile, which must be activated explicitly:
```bash
export DB_PASSWORD=...          # same value as in .env
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```
The dev profile enables Swagger UI and generates a random `JWT_SECRET` per run when unset.

Health check: `http://localhost:8080/actuator/health`

## Configuration (authentication)
| Variable | Purpose | Default |
|----------|---------|---------|
| `JWT_SECRET` | HS256 signing secret for API tokens. **Required in prod**, at least 32 bytes (`openssl rand -base64 48`). The app refuses to start without it. In the dev profile a random secret is generated per run when unset (tokens reset on restart). | none |
| `APP_BASE_URL` | Public address of this install, used to build invitation links, e.g. `https://crm.example.com`. **Required in prod** (absolute http(s) URL, no query or fragment). Dev profile defaults to `http://localhost:8080`. | none |
| `INVITATION_TTL` | How long an invitation link stays valid | `7d` |
| `JWT_ACCESS_TOKEN_TTL` | Access token lifetime | `15m` |
| `JWT_REFRESH_TOKEN_TTL` | Refresh token lifetime | `7d` |
| `LOGIN_MAX_ATTEMPTS` / `LOGIN_LOCKOUT_DURATION` | Temporary lockout after repeated failed logins | `5` / `15m` |
| `SESSION_COOKIE_SECURE` | Set `true` behind HTTPS | `false` |
| `REFRESH_TOKEN_CLEANUP_ENABLED` / `_INTERVAL` | Periodic deletion of expired refresh tokens (on in prod) | `true` / `1h` |

## API authentication
Web UI: session login at `/login`. API: JWT bearer tokens.

- `POST /api/auth/login` `{"email","password"}` returns `accessToken`, `refreshToken`, `tokenType`, `expiresIn`.
- `POST /api/auth/refresh` `{"refreshToken"}` rotates the refresh token; reusing an old one revokes the whole token family.
- `POST /api/auth/logout` (public, refresh token in the body is the credential) returns 204.
- `GET /api/auth/me` (bearer) returns the current user.
- `POST /api/auth/change-password` (bearer) `{"currentPassword","newPassword"}` revokes all refresh tokens.
- `GET /api/users/me/activity?page=0&size=20` (bearer) returns your own audit entries (size max 100).

## Users, invitations and roles
Roles are global: **Admin**, **Manager**, **Member**. There is no self-registration; people join through a one-time invitation link (no email is sent: the inviter copies the link).

- `GET /api/users`, `GET /api/users/{id}` (Admin, Manager); `PATCH /api/users/{id}`; `PATCH /api/users/{id}/role` and `DELETE /api/users/{id}` (Admin).
- `POST /api/invitations` returns the one-time `acceptUrl`; `GET /api/invitations`; `DELETE /api/invitations/{id}`.
- Invitees open `/invite/{token}` (or call `POST /api/invitations/accept`) to set a name and password.
- `GET /api/audit` (Admin) is the audit log of all users.

## Teams
Teams group people inside the one organization (they are not tenants). A user can be in several teams, with a team role of Lead or Member in each. For now teams only group people; they do not limit which records anyone sees.

- `GET /api/teams`, `GET /api/teams/{id}` (Admin: all teams; everyone else: the teams they belong to); `POST`, `PATCH`, `DELETE /api/teams/{id}` (Admin).
- `GET /api/teams/{id}/members`, `PUT` and `DELETE /api/teams/{id}/members/{userId}` (Admin; a Manager who leads the team, for Member accounts only); `GET /api/users/{id}/teams`.

## Admin web pages
Sign in and use the navigation: `/admin/users` (Admin, Manager: users, invitations), `/admin/teams` (everyone, filtered by what you may see) and `/admin/audit` (Admin). Server-rendered with HTMX; no extra setup. The invitation link is shown once when you create it. Who may do what: [docs/rbac.md](docs/rbac.md).

Role and permission matrix, who may act on whom, error codes: [docs/rbac.md](docs/rbac.md).

Details, error codes and curl examples: [docs/auth.md](docs/auth.md). API docs (dev profile): `/swagger-ui.html` after signing in.

All `/api/**` responses use one envelope (`status`, `message`, `errorCode`, `data`, `errors`, `path`, `timestamp`); clients should branch on the HTTP status.

## Roadmap
- [x] Project skeleton, Docker Compose, Flyway, CI
- [x] Authentication (web session + API JWT), first-run setup, audit log
- [x] Roles (RBAC), user management and invitations (API)
- [x] Teams and team membership (API), admin web UI (users, invitations, teams, audit log)
- [ ] Contacts, companies, deals
- [ ] Activities and notes
- [x] REST API with JWT (auth endpoints)
- [ ] AI assistant (Spring AI + Groq)

## Contributing
See [CONTRIBUTING.md](CONTRIBUTING.md). Security issues: [SECURITY.md](SECURITY.md).

## License
[AGPL-3.0](LICENSE)
