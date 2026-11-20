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
cp .env.example .env            # then set DB_PASSWORD and JWT_SECRET
docker compose --profile app up -d --build
# open http://localhost:8080 (the first visit asks you to create the administrator)
```

`docker compose up -d` without `--profile app` starts only PostgreSQL, which is what you want for local development (run the app from your IDE or `./mvnw spring-boot:run`).

Health check: `http://localhost:8080/actuator/health`

## Configuration (authentication)
| Variable | Purpose | Default |
|----------|---------|---------|
| `JWT_SECRET` | HS256 signing secret for API tokens. **Required in prod**, at least 32 bytes (`openssl rand -base64 48`). The app refuses to start without it. In the dev profile a random secret is generated per run when unset (tokens reset on restart). | none |
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

Details, error codes and curl examples: [docs/auth.md](docs/auth.md). API docs (dev profile): `/swagger-ui.html` after signing in.

All `/api/**` responses use one envelope (`status`, `message`, `errorCode`, `data`, `errors`, `path`, `timestamp`); clients should branch on the HTTP status.

## Roadmap
- [x] Project skeleton, Docker Compose, Flyway, CI
- [x] Authentication (web session + API JWT), first-run setup, audit log
- [ ] Roles (RBAC) and teams
- [ ] Contacts, companies, deals
- [ ] Activities and notes
- [x] REST API with JWT (auth endpoints)
- [ ] AI assistant (Spring AI + Groq)

## Contributing
See [CONTRIBUTING.md](CONTRIBUTING.md). Security issues: [SECURITY.md](SECURITY.md).

## License
[AGPL-3.0](LICENSE)
