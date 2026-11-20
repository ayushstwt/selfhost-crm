# Contributing to Selfhost CRM

Thanks for helping! Please read the [Code of Conduct](CODE_OF_CONDUCT.md) first.

## Development setup
Requirements: JDK 21, Docker.

```bash
cp .env.example .env
docker compose up -d db      # PostgreSQL only
./mvnw spring-boot:run       # run the app from the CLI (or your IDE)
./mvnw verify                # build + tests (Testcontainers, needs Docker)
```

## Workflow
1. Open or pick an issue; discuss larger changes before coding.
2. Branch from `main` (never commit to `main` directly), e.g. `feat/contact-list`.
3. Keep PRs small and focused.
4. Use [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`, `chore:`, `test:`, `ci:`, `refactor:`).
5. Open a PR using the template; CI must pass.

## Rules of the codebase
- Layered: controller -> service -> repository; DTOs at the web boundary; constructor injection.
- Single tenant: no tenant IDs or switching.
- Schema changes only through new Flyway migrations; never edit an applied one.
- Every feature ships with tests; use Testcontainers for database tests.
- No secrets in git; validate all input; passwords use BCrypt.

See [CLAUDE.md](CLAUDE.md) for the full conventions.

## License
By contributing you agree your contributions are licensed under AGPL-3.0.
