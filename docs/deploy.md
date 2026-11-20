# Deployment guide

Selfhost CRM runs as two containers (the app and PostgreSQL) from one Compose file. One install serves one organization. These steps were run end to end against the image built from this repository (see [Smoke test](#smoke-test-checklist)).

Requirements: Docker with Compose v2, and a host where you can open ports 80/443 if you want HTTPS.

## 1. Clone and configure

```bash
git clone https://github.com/ayushstwt/selfhost-crm.git
cd selfhost-crm
cp .env.example .env
```

Edit `.env` (it is git-ignored; never commit it):

| Variable | What to set |
|----------|-------------|
| `DB_USER` | Required. Any name, for example `selfhostcrm`. |
| `DB_PASSWORD` | Required, no default. At least 12 characters and not `change-me`, `selfhostcrm`, `postgres` or `password`. The app refuses to start otherwise. `openssl rand -base64 24` |
| `JWT_SECRET` | Required, at least 32 bytes. `openssl rand -base64 48` |
| `APP_BASE_URL` | Required. The public address people use to reach the app, for example `https://crm.example.com` (or `http://localhost:8080` for a local test). Invitation links are built from it. Must be an absolute `http`/`https` URL without credentials, query or fragment; a trailing slash is ignored. The app refuses to start in prod without it. Set it to your HTTPS domain once the proxy is in place (step 4), otherwise invitation links point to the wrong address. |
| `SESSION_COOKIE_SECURE` | `true` once you serve the app over HTTPS (see step 4). |

The other variables have defaults; `.env.example` documents all of them.

> **Shell variables win over `.env`.** Compose gives an exported shell variable (for example a leftover `DB_USER` or `DB_PASSWORD` from another project) priority over the same name in `.env`. If the app complains about a password that looks right, run `env | grep -E '^(DB_|JWT_|SERVER_PORT)'` and `unset` the stray variables, or check with `docker compose --profile app config`.

## 2. Start

```bash
docker compose --profile app up -d --build
docker compose ps          # wait until both services are "healthy" (the app needs up to ~45 s on first start)
curl -s http://localhost:8080/actuator/health    # {"status":"UP",...}
```

- The app runs with the `prod` profile (the default). Swagger UI and `/v3/api-docs` are disabled.
- PostgreSQL is published on `127.0.0.1:${DB_PORT:-5432}` only, so it is not reachable from the network. The app talks to it over the Compose network.
- The app container runs as a non-root user and has a healthcheck on `/actuator/health`. The app waits for the database to be healthy before starting.

## 3. First-run setup

Open `http://localhost:8080` (or your domain). While there are no users every page redirects to `/setup`, where you create the first administrator (display name, email, password of 12 to 128 characters). After that `/setup` returns 404 for good and you sign in at `/login`. See [auth.md](auth.md).

## 4. Reverse proxy and HTTPS

Put a TLS-terminating reverse proxy in front of the app. The app (prod profile) honours `X-Forwarded-Proto`, `X-Forwarded-Host`, `X-Forwarded-Port` and `X-Forwarded-For`, so redirects and generated URLs use the public scheme and host. **The proxy must set these headers and must be the only way in**: the app port (`SERVER_PORT`, default 8080) is published on all interfaces by Compose, so block it with a firewall (or only reach it via the proxy on the same host) to stop clients sending forged forwarded headers.

Caddy (sets the `X-Forwarded-*` headers and obtains certificates automatically):

```caddyfile
crm.example.com {
    reverse_proxy localhost:8080
}
```

Then set `APP_BASE_URL=https://crm.example.com` and `SESSION_COOKIE_SECURE=true` in `.env` and apply it with `docker compose --profile app up -d`. nginx users must add `proxy_set_header X-Forwarded-Proto $scheme; proxy_set_header X-Forwarded-Host $host; proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`.

Per-IP rate limiting on `/login` and `/api/auth/*` belongs at the proxy (see [auth.md](auth.md)).

## 5. Backup and restore

Data lives in the Docker volume `selfhost-crm_pgdata`. Back up regularly and keep copies off the host.

Logical backup (recommended; works while the stack is running):

```bash
docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists' > backup-$(date +%F).sql
```

Restore into a running, empty database:

```bash
docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < backup-2026-01-01.sql
```

Raw volume snapshot (stop the database first so the files are consistent):

```bash
docker compose --profile app stop app db
docker run --rm -v selfhost-crm_pgdata:/data -v "$PWD":/backup alpine tar czf /backup/pgdata-$(date +%F).tar.gz -C /data .
docker compose --profile app start db app
```

Test a restore on a scratch machine before you need it. `JWT_SECRET` and `.env` are not in the database: back them up separately and securely.

## 6. Upgrade

```bash
git pull
docker compose --profile app up -d --build     # rebuilds the image, restarts the app; Flyway migrates the schema on start
docker compose ps
```

Take a backup first (step 5). Read the release notes for new variables and compare `.env` against `.env.example`. To roll back, check out the previous version and rebuild; a schema migration that already ran is not reverted, so restore the backup if the new version changed the schema.

`docker compose --profile app down` stops and removes the containers and keeps the data. `down -v` also deletes the database volume.

## Local development

See the README: start only the database with `docker compose up -d db`, export `DB_PASSWORD`, then run `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`.

## Smoke test checklist

Run after changing the Dockerfile or Compose file, with a throw-away env file outside the repository and its own Compose project name so nothing of yours is touched:

```bash
T=$(mktemp -d)
printf 'DB_USER=smoke\nDB_PASSWORD=%s\nJWT_SECRET=%s\nAPP_BASE_URL=http://localhost:18080\nSERVER_PORT=18080\nDB_PORT=15432\n' \
  "$(openssl rand -hex 16)" "$(openssl rand -base64 48 | tr -d '\n')" > "$T/smoke.env"
docker compose -p smoketest --env-file "$T/smoke.env" --profile app up -d --build
# check: /actuator/health UP; /setup creates the admin; /login works; POST /api/auth/login returns tokens;
# POST /api/invitations returns an acceptUrl that starts with APP_BASE_URL and /invite/ opens;
# /swagger-ui.html and /v3/api-docs are not served (302 anonymous, 404 signed in);
# 5432/15432 refuse connections on the LAN address; `docker exec smoketest-app-1 id` is not root;
# `docker restart smoketest-app-1` comes back healthy with the data intact.
docker compose -p smoketest --env-file "$T/smoke.env" --profile app down -v
docker rmi smoketest-app; rm -rf "$T"
```
