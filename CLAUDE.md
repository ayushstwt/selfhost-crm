# CLAUDE.md

## Project purpose
Selfhost CRM is an open-source (AGPL-3.0), self-hosted CRM with built-in AI (Spring AI + Groq, planned). One install serves exactly one organization. Self-hosters must be able to run `git clone`, `cp .env.example .env`, `docker compose up -d`.

## Stack
Spring Boot 4.1.x, Java 21, Maven (wrapper), YAML config, PostgreSQL, Flyway, Spring Data JPA, Spring Security, Thymeleaf + HTMX (server-rendered; no React or separate frontend), Testcontainers. Web UI: session cookies + CSRF. API: JWT.

## Single-tenant rule
No tenant IDs, no tenant columns, no tenant switching, no per-tenant config. Do not add multi-tenancy abstractions. Roles: Admin, Manager, Member, plus teams.

## Roles and permissions
- Roles are global (ADMIN, MANAGER, MEMBER). `RolePermissions` is the single role-to-`Permission` mapping; both security chains expose `ROLE_<role>` plus one authority per permission. The JWT carries only the role.
- Enforce with `@PreAuthorize("hasAuthority('X')")` on **service** methods; target-based rules (who may act on whom, self-actions, last-admin guard) live in `UserAccessPolicy`. Controllers stay thin and pass the acting `UserPrincipal` (the API principal is `UserPrincipal`, not `Jwt`).
- Deny by default: `ApiSecurityConfig` lists every endpoint's permission and ends with `anyRequest().hasRole("ADMIN")`. A new endpoint needs a URL rule, a `@PreAuthorize`, and a row in the role matrix test.
- Changing role, enabled or deleted state goes through `AccessRevocationService` (bumps `users.access_changed_at`, revokes refresh tokens), after taking the admin lock in `UserManagementService`. Details: `docs/rbac.md`.

## Backend guide
`docs/Best_practices_backend.md` is the source of truth for API responses, exception handling and JPA entities. Read it before touching those areas. Where it uses the placeholder package `com.example.app` or the folder names `controller`, `dto`, `entity`, use the mapping in "Package layout" below. If this file and the guide disagree, this file wins.

## Package layout
Base package `com.ayshriv.selfhostcrm`.
- `web` - controllers, request/response DTOs and view models. The API envelope records (`ApiResponse`, `ApiError`, `PageMetadata`) live here.
- `service` - business logic, transactions
- `repository` - Spring Data repositories
- `domain` - JPA entities and enums, including `BaseEntity`
- `exception` - `BaseException` and subclasses, `ControllerExceptionHandler`
- `constants` - `ErrorMessages` enum
- `config` - Spring configuration (security, etc.)

Group by feature inside each layer once features exist (e.g. `web.contact`). Migrations: `src/main/resources/db/migration`. Templates: `src/main/resources/templates`.

## Commands
- Build + tests: `./mvnw verify` (tests need Docker running)
- Tests only: `./mvnw test`
- Run app locally (needs Postgres): `docker compose up -d db`, export `DB_PASSWORD` (same value as in `.env`), then `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`. **prod is the default profile**; dev must be activated explicitly. Prod refuses weak/missing `DB_PASSWORD` and a missing `JWT_SECRET`.
- Tests must declare their profile (`@ActiveProfiles("dev")` or `"prod"`); never rely on the default.
- Full stack in containers: `docker compose --profile app up -d --build`
- Validate compose: `docker compose config`

## Coding conventions
- Layered: controller -> service -> repository. Controllers never touch repositories.
- DTOs at the web boundary; never expose or bind JPA entities directly.
- Constructor injection only (no field `@Autowired`).
- Schema changes only via Flyway migrations (`V<n>__description.sql`). Never edit an applied migration; add a new one. `ddl-auto` stays `validate`.
- Keep `spring.jpa.open-in-view` false.

## API responses (/api/**)
- Every endpoint returns `ResponseEntity<ApiResponse<T>>` with the correct HTTP status. Clients branch on the HTTP status; the body `status` is informational only.
- List endpoints include `PageMetadata` (`currentPage`, `pageSize`, `totalPages`, `totalItems`) with these exact names.
- Create returns 201 with a Location header; delete returns 204 with no body.
- JSON is camelCase everywhere. Timestamps are ISO-8601 `Instant` (UTC). No per-field `@JsonFormat` or `@JsonProperty` naming tricks.
- Document response examples in springdoc/OpenAPI.

## Exceptions
- Throw, never hand-build error responses in controllers.
- One base class, `BaseException`, carrying an `ErrorMessages` constant. Subclasses (`ResourceNotFoundException`, `ResourceAlreadyExistException`, `UnauthorizedException`, `ForbiddenException`, `ServiceException`, `AppException`) only add intent; no duplicated classes.
- `ErrorMessages` holds the stable error code (constant name), `HttpStatus` and message template. Adding an error means adding a constant.
- One `ControllerExceptionHandler` restricted to REST controllers. It returns the single `ApiResponse` error shape (with `errorCode`, `path`, and `errors[]` for field validation).
- The catch-all handler logs the exception and returns a generic message. Never return `ex.getMessage()` to clients.
- Spring's own 4xx (405, 415, missing parameter, type mismatch, unreadable JSON, unknown route) must stay 4xx, never become 500.
- 4xx are logged at `warn`; 5xx at `error` with the stack trace.
- Spring Security 401 and 403 on the API chain are written in the same `ApiResponse` shape (custom `AuthenticationEntryPoint` and `AccessDeniedHandler`).
- Web (Thymeleaf) requests get a separate `@ControllerAdvice` that renders friendly HTML error pages with no stack traces.

## Entities
- Entities extend `BaseEntity` (`@MappedSuperclass`): `Long id` (IDENTITY), `Instant createdOn`, `Instant updatedOn`, boolean `active` and `deleted` (columns `is_active`, `is_deleted`).
- Use `@Getter`/`@Setter`, not `@Data`. `equals`/`hashCode` are id-based.
- Defaults come from field initializers; callbacks only set timestamps. Never set `id`, `createdOn` or `updatedOn` by hand.
- No Jackson annotations on entities. Entities are never serialized.
- Lowercase snake_case column names. The JVM and Hibernate run in UTC (`hibernate.jdbc.time_zone=UTC`).
- Soft delete: use `@SQLRestriction("is_deleted = false")` on entities that support it (`User`). Append-only/never-soft-deleted entities (`UserLog`, `RefreshToken`, `Invitation`) have no filter on purpose and say so in a class comment. Never load a lazy `User` reference from them (it may be soft-deleted): store the id or use a DTO projection with a LEFT JOIN. Bulk `UPDATE` queries must set `updated_on` explicitly.
- Email uniqueness is a partial unique index on live users, so a deleted user's email is reusable.
- Subclass overrides of lifecycle callbacks must call `super`.

## Audit log
- `UserLog`: lazy `@ManyToOne` user (the actor, always set server-side), `action` and `subAction` as enums or constants (never free text), optional `targetType`/`targetId`, index on `(user_id, created_on)`. Adding a `UserAction` value needs a migration that replaces `ck_user_log_action` (a test fails on drift).
- Read through a DTO projection, paginated, ordered by `createdOn` desc. No transient fields.
- Never store passwords, tokens or request bodies in the log.

## Testing
Every feature ships with tests. Use Testcontainers (PostgreSQL) for anything touching the DB; do not use H2. Pin container image to the same major version as Compose. Include serialization tests proving responses never expose `active`, `deleted`, password hashes or tokens.

## Security
- No secrets in git; config comes from environment variables (`.env` is git-ignored, `.env.example` documents them).
- Passwords hashed with BCrypt. No user enumeration: wrong email and wrong password give the same error.
- Validate all input (Bean Validation at the web boundary).
- Expose only what is needed (Actuator: health only).
- Never read `.env` files.
- No secret value is ever committed, not even a "dev default". `JWT_SECRET` is required in prod; the dev profile generates a random one per run. Details in `docs/auth.md`.
- Login failures must be indistinguishable: wrong password, unknown email and a locked account return the same generic error (`E006`). Lockouts are only logged server-side.
- `AuthService` must NOT be `@Transactional` (failure counters and audit rows are committed before it throws). `AuthServiceTransactionTest` enforces this.
- Changing a password sets `users.credentials_changed_at`: refresh tokens are revoked and other web sessions end (`ActiveUserSessionFilter`).
- `APP_BASE_URL` (absolute http(s) URL, validated in prod) builds invitation links. Invitation tokens are 256-bit random, only the SHA-256 is stored, and every unusable token returns the same `E016`.
- New web pages must put the CSRF token on `<body hx-headers=...>` (copy from `templates/login.html`) so HTMX requests carry it.

## Git
- The maintainer commits manually. Claude Code must NEVER run `git add`, `git commit` or `git push`, and must NEVER add `Co-Authored-By`, "Generated with Claude Code" or any similar attribution to commits, PRs, code or docs.
- After each step: list the changed files and give a suggested Conventional Commit message. Leave all changes uncommitted.
- Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, `test:`, `ci:`, `refactor:`).
- Small, focused PRs. Never commit directly to `main`; use a branch and open a PR.