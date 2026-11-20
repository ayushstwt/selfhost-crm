# Security Policy

## Supported versions
Selfhost CRM is in early development. Only the latest commit on `main` is supported.

## Reporting a vulnerability
Please do **not** open a public issue for security problems.

Use GitHub's private vulnerability reporting:
<https://github.com/ayushstwt/selfhost-crm/security/advisories/new>

Include a description, steps to reproduce, affected version/commit, and the impact you foresee. We aim to acknowledge reports within 7 days and will coordinate a fix and disclosure with you.

## Self-hosting hygiene
- Change `DB_PASSWORD` in `.env` before deploying; never commit `.env`.
- Run behind HTTPS (reverse proxy).
- Keep the Docker images and dependencies up to date.
