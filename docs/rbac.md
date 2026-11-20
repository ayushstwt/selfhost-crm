# Roles, permissions, users and invitations

Roles are **global** (one install = one organization): `ADMIN`, `MANAGER`, `MEMBER`. Teams come in a later release. Authentication is described in [auth.md](auth.md).

Contents: [Permissions](#permissions) · [Who may act on whom](#who-may-act-on-whom) · [How it is enforced](#how-it-is-enforced) · [Endpoints](#endpoints) · [403 versus 404](#403-versus-404) · [Last-admin guard](#last-admin-guard) · [Access changes take effect at once](#access-changes-take-effect-at-once) · [Invitation flow](#invitation-flow) · [Audit](#audit) · [Error codes](#error-codes)

## Permissions

`RolePermissions` is the only place that maps a role to permissions. The JWT carries only the role; permissions are derived on the server and exposed as authorities named exactly like the permission (`USER_VIEW`, ...), next to `ROLE_<role>`, on both the API and the web chain.

| Permission | ADMIN | MANAGER | MEMBER |
|------------|:-----:|:-------:|:------:|
| `USER_VIEW` (list and read users) | yes | yes | no |
| `USER_UPDATE` (display name, enabled) | yes | yes, limited (below) | no |
| `USER_INVITE` | yes | yes, Member only | no |
| `USER_ROLE_CHANGE` | yes | no | no |
| `USER_DELETE` | yes | no | no |
| `INVITATION_MANAGE` (list, revoke) | yes, all | yes, own only | no |
| `AUDIT_VIEW_ALL` (`GET /api/audit`) | yes | no | no |

Every signed-in user, whatever the role, can use their own account: `GET /api/auth/me`, `POST /api/auth/change-password`, `GET /api/users/me/activity`.

## Who may act on whom

Permissions say what a role may do in general. `UserAccessPolicy` decides whether the actor may do it to **this** user.

| Action | ADMIN | MANAGER | Nobody |
|--------|-------|---------|--------|
| View a user | any | any | |
| Edit display name | any user | Member accounts and own name | |
| Enable / disable | any user except self | Member accounts only | disable yourself |
| Change role | any user except self | never | change your own role |
| Delete | any user except self | never | delete yourself |
| Invite | any role | Member only | |
| Revoke an invitation | any | own only | |
| Last enabled Admin: demote, disable, delete | blocked (409) | n/a | |

A Manager can never create, edit, change, disable or delete an Admin or another Manager, and can never change any role. A Manager may still *view* Admins and Managers.

## How it is enforced

Two layers, deny by default:

1. **URL rules** (`ApiSecurityConfig`): each known endpoint requires the matching permission; the self-service endpoints require authentication only; `anyRequest()` requires `ROLE_ADMIN`. A new endpoint without a rule is therefore reachable by ADMIN only. Public: `POST /api/auth/login|refresh|logout` and `POST /api/invitations/accept`.
2. **Service layer**: `@PreAuthorize("hasAuthority('USER_VIEW')")` and friends on `UserManagementService`, `InvitationService` and `AuditQueryService`, plus the `UserAccessPolicy` checks for the target. Controllers stay thin (bind, call the service, wrap the response). Services receive the acting `UserPrincipal` as a parameter; the API principal carries id and role without any extra query (it is the row already loaded for the request).

When you add an endpoint: add its rule in `ApiSecurityConfig`, put `@PreAuthorize` on the service method, and add it to the role matrix test.

## Endpoints

| Endpoint | Allowed | Success |
|----------|---------|---------|
| `GET /api/users?role=&enabled=&q=&page=&size=` | Admin, Manager | 200, paged (size max 100), newest first |
| `GET /api/users/{id}` | Admin, Manager | 200 |
| `PATCH /api/users/{id}` `{displayName?, enabled?}` | Admin, Manager (rules above) | 200 |
| `PATCH /api/users/{id}/role` `{role}` | Admin | 200 |
| `DELETE /api/users/{id}` | Admin | 204 (soft delete) |
| `POST /api/invitations` `{email, role}` | Admin, Manager | 201 + `Location`, one-time `acceptUrl` |
| `GET /api/invitations` | Admin (all), Manager (own) | 200, pending only |
| `DELETE /api/invitations/{id}` | Admin (any), Manager (own) | 204 |
| `POST /api/invitations/accept` `{token, displayName, password}` | public | 201 |
| `GET` / `POST /invite/{token}` | public web page | form / redirect to `/login?invited` |
| `GET /api/audit` | Admin | 200, paged |

`q` matches display name or email, case-insensitively and literally (`%` and `_` are not wildcards). A deleted user disappears from the list and returns 404. The user list is read in a constant number of queries (no N+1), asserted by a test.

## 403 versus 404

- A caller who lacks the permission, or who tries something outside their scope (a MEMBER asking for another user, a MANAGER acting on an ADMIN or revoking someone else's invitation), gets **403**. Role-level answers do not depend on whether the target exists.
- **404** is returned only to callers who are allowed to use the endpoint, and only for ids that do not exist (or are deleted, or for an invitation that is no longer pending).
- Why 403 and not 404 for out-of-scope targets: user ids are sequential and a Manager may list every user anyway, so hiding existence would add confusion and protect nothing. The self-service endpoints never take an id, so a user cannot probe others through them.
- Unauthenticated requests are 401. Invitation tokens are different: every unusable token gets the same 400 (`E016`), never a 404 or 403, so nothing reveals why.

## Last-admin guard

The last **enabled** Admin can never be demoted, disabled or deleted (409, `E014`). Self-actions are already blocked, so the guard matters when two Admins act on each other at the same moment. Every role, enabled and delete change first locks *all* enabled Admin rows (`SELECT ... FOR UPDATE`, in id order, so there is no deadlock) and then re-checks the count, so two concurrent requests cannot both succeed (tested with two Admins demoting each other in parallel).

## Access changes take effect at once

Changing a role, disabling, enabling or deleting a user:

- sets `users.access_changed_at` (access tokens issued before it and older web sessions are rejected, see [auth.md](auth.md));
- revokes all of the user's refresh tokens;
- is audited in the same transaction.

A demoted Admin loses admin pages on the very next request, and their existing API tokens stop working. They have to sign in again (a sign-in inside the same clock second as the change is rejected and must be repeated).

Deleting a user also revokes the pending invitations they created. Deleted users keep their row (audit history stays intact); only the email becomes reusable.

## Invitation flow

No email is sent. The inviter receives the link once and passes it on.

1. `POST /api/invitations {email, role}` (Admin: any role, Manager: Member only). Response `201` with `Location` and `acceptUrl = APP_BASE_URL + /invite/<token>`. The token is 256 random bits, URL-safe (43 characters). **Only its SHA-256 is stored**, so the link cannot be shown again; if it is lost, create a new invitation.
2. TTL is 7 days (`INVITATION_TTL`). Creating a new invitation for the same email revokes the previous open one (one pending invitation per email, enforced by a partial unique index). A Manager cannot replace a live invitation created by someone else (409, `E017`); an Admin can. An email that belongs to an existing live user returns 409 (`E002`).
3. The invitee opens `/invite/<token>` (or calls `POST /api/invitations/accept`), chooses a display name and a password (12 to 128 characters, not equal to the email) and the account is created with exactly the invited role. The page has CSRF protection and the `hx-headers` token on `<body>`, is sent with `Cache-Control: no-store` and `Referrer-Policy: no-referrer` (the token is in the URL), and is not affected by the first-run setup redirect.
4. A link works once. Unknown, expired, used, revoked and replaced tokens all produce the same error (`E016`, HTTP 400 on the API, the same generic page on the web), whatever the reason.
5. The invitee then signs in normally at `/login` or `POST /api/auth/login`.

Operations notes: set `APP_BASE_URL` to the public HTTPS address, otherwise the generated links point to the wrong host. Treat the link like a password until it is used. Per-IP rate limiting of the public accept endpoint belongs at the reverse proxy (see [auth.md](auth.md)).

## Audit

`GET /api/audit` (Admin only) returns all entries newest first: `id`, `createdOn`, `action`, `subAction`, `actorId`, `actorName`, `targetType` (`USER` or `INVITATION`), `targetId`. Filters: `actorId`, `action`, `targetType`, `targetId`, `from`, `to` (ISO-8601 instants, inclusive; `from` after `to` is a 400), `page`, `size` (max 100). `actorName` is `null` when the actor was deleted later; the entry stays. Administrative actions are `USER_UPDATED`, `USER_ROLE_CHANGED`, `USER_DISABLED`, `USER_ENABLED`, `USER_DELETED`, `INVITATION_CREATED`, `INVITATION_REVOKED` (also written when an invitation is replaced), `INVITATION_ACCEPTED` (actor = the new user). `GET /api/users/me/activity` is unchanged and shows a user only their own entries. Tokens, passwords and request bodies are never logged.

## Error codes

| Code | HTTP | When |
|------|------|------|
| `E004` | 403 | Missing permission (also an unlisted route for non-admins) |
| `E013` | 403 | Allowed endpoint, but not on this target (Manager on Admin/Manager, role change by a non-Admin, invitation of someone else) |
| `E015` | 403 | Changing your own role, disabling or deleting yourself |
| `E014` | 409 | Would remove the last enabled Admin |
| `E002` | 409 | Invitation for an email that already has a live account |
| `E017` | 409 | Manager tries to replace a live invitation made by someone else |
| `E016` | 400 | Invitation token invalid, expired, used, revoked or replaced |
| `E001` | 404 | Unknown (or deleted) user, or invitation that is not pending |
| `E005` | 400 | Validation (including an empty `PATCH`, `size` above 100, `from` after `to`) |

The full list is in [auth.md](auth.md#error-codes).
