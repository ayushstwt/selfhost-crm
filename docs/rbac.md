# Roles, permissions, users, invitations and teams

Roles are **global** (one install = one organization): `ADMIN`, `MANAGER`, `MEMBER`. **Teams** are groups of people inside that one organization (not tenants): a user can belong to several teams, with a team role `LEAD` or `MEMBER` in each. Teams only group people for now; they do not yet limit which records someone sees. Authentication is described in [auth.md](auth.md).

Contents: [Permissions](#permissions) · [Who may act on whom](#who-may-act-on-whom) · [Teams](#teams) · [How it is enforced](#how-it-is-enforced) · [Endpoints](#endpoints) · [Admin web pages](#admin-web-pages) · [403 versus 404](#403-versus-404) · [Last-admin guard](#last-admin-guard) · [Access changes take effect at once](#access-changes-take-effect-at-once) · [Invitation flow](#invitation-flow) · [Audit](#audit) · [Error codes](#error-codes)

## Permissions

`RolePermissions` is the only place that maps a role to permissions. The JWT carries only the role; permissions are derived on the server and exposed as authorities named exactly like the permission (`USER_VIEW`, ...), next to `ROLE_<role>`, on both the API and the web chain.

| Permission | ADMIN | MANAGER | MEMBER |
|------------|:-----:|:-------:|:------:|
| `USER_VIEW` (list and read users) | yes | yes | no |
| `USER_UPDATE` (display name, enabled) | yes | yes, limited (below) | no |
| `USER_INVITE` | yes | yes, Member only | no |
| `USER_ROLE_CHANGE` | yes | no | no |
| `USER_DELETE` | yes | no | no |
| `INVITATION_MANAGE` (list, read, revoke) | yes, all | yes, own only | no |
| `AUDIT_VIEW_ALL` (`GET /api/audit`, `/admin/audit`) | yes | no | no |
| `TEAM_VIEW_ALL` (see every team) | yes | no | no |
| `TEAM_MANAGE` (create, rename, delete teams) | yes | no | no |
| `TEAM_MEMBERS_MANAGE` (add, remove, change team role) | yes, any team | yes, only teams they lead (below) | no |

Every signed-in user, whatever the role, can use their own account (`GET /api/auth/me`, `POST /api/auth/change-password`, `GET /api/users/me/activity`) and can read **the teams they belong to** (no permission needed: the team rules below decide).

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
| Revoke / read an invitation | any | own only | |
| Last enabled Admin: demote, disable, delete | blocked (409) | n/a | |

A Manager can never create, edit, change, disable or delete an Admin or another Manager, and can never change any role. A Manager may still *view* Admins and Managers.

## Teams

`TeamAccessPolicy` decides who may see a team and who may change its members. The caller's memberships (team id and team role) are part of the principal (`UserPrincipal.getMemberships()`), loaded together with the user row on every request, so the checks cost no extra query.

| Action | ADMIN | MANAGER | MEMBER |
|--------|-------|---------|--------|
| List teams | all | only teams they belong to | only teams they belong to |
| See a team, its member count and members | any team | teams they belong to | teams they belong to |
| Create, rename, delete a team | yes | no | no |
| Add / remove a user, change a team role | any user, any team | only in teams where they are **LEAD**, and only users whose **account role is MEMBER** | no |
| Change a team role | LEAD or MEMBER for anyone | between MEMBER and LEAD, Member accounts only | no |
| `GET /api/users/{id}/teams` | any user | themselves only | themselves only |

More rules:

- A Manager cannot add or remove Admin or Manager accounts, **cannot remove themselves**, and cannot touch a team where they are only a plain member (or not a member at all).
- A MEMBER account with team role LEAD gains **no** management rights: leads manage members only when their account role is MANAGER (or ADMIN).
- A team can have several leads or none. A user can be in any number of teams.
- `PUT /api/teams/{id}/members/{userId}` is idempotent: it creates the membership (201) or sets the team role of an existing one (200). `(team, user)` is unique in the database as well.
- A **disabled or deleted user is not listed** as a member and is not counted; a disabled user cannot be added (409 `E022`), a deleted or unknown one is 404. Membership rows of a disabled user are kept, so re-enabling brings them back.
- Removing a member deletes the membership row (audited). Deleting a team **soft-deletes the team and all its memberships**; its name can be used again. Team names are unique ignoring case among live teams.
- Member emails are only shown to callers who hold `USER_VIEW`; plain members see display name, account role and team role.
- Models for later record scoping: use `UserPrincipal#teamIds()` / `isMemberOf` / `isLeadOf` (no query) and `TeamMemberRepository#findMemberships` / the index on `team_members(user_id)`.

## How it is enforced

Two layers, deny by default:

1. **URL rules** (`ApiSecurityConfig`, `WebSecurityConfig`): each known endpoint requires the matching permission, or only authentication where the service limits the result (team reads, self-service). `anyRequest()` on the API requires `ROLE_ADMIN`; under `/admin/**` any route without a rule is ADMIN only. Public: `POST /api/auth/login|refresh|logout` and `POST /api/invitations/accept`.
2. **Service layer**: `@PreAuthorize("hasAuthority('TEAM_MANAGE')")` and friends on `UserManagementService`, `InvitationService`, `AuditQueryService` and `TeamService`, plus the `UserAccessPolicy` / `TeamAccessPolicy` checks for the target. Controllers (REST and admin pages) stay thin: bind, call the service, wrap the response. Hiding a link or button in a page is never the security check.

When you add an endpoint: add its URL rule, put `@PreAuthorize` on the service method, and add it to the role matrix test.

## Endpoints

| Endpoint | Allowed | Success |
|----------|---------|---------|
| `GET /api/users?role=&enabled=&q=&page=&size=` | Admin, Manager | 200, paged (size max 100), newest first |
| `GET /api/users/{id}` | Admin, Manager | 200 |
| `PATCH /api/users/{id}` `{displayName?, enabled?}` | Admin, Manager (rules above) | 200 |
| `PATCH /api/users/{id}/role` `{role}` | Admin | 200 |
| `DELETE /api/users/{id}` | Admin | 204 (soft delete) |
| `GET /api/users/{id}/teams` | Admin, or the user themselves | 200, paged |
| `POST /api/invitations` `{email, role}` | Admin, Manager | 201 + `Location`, one-time `acceptUrl` |
| `GET /api/invitations` | Admin (all), Manager (own) | 200, pending only |
| `GET /api/invitations/{id}` | Admin (any), Manager (own) | 200 (the `Location` target of the create call; 404 once no longer pending) |
| `DELETE /api/invitations/{id}` | Admin (any), Manager (own) | 204 |
| `POST /api/invitations/accept` `{token, displayName, password}` | public | 201 |
| `GET` / `POST /invite/{token}` | public web page | form / redirect to `/login?invited` |
| `GET /api/teams?q=&page=&size=` | any signed-in user (Admin: all, others: own teams) | 200, paged (size max 100), by name |
| `GET /api/teams/{id}` | Admin, or a member of the team | 200 with `memberCount` |
| `POST /api/teams` `{name, description?}` | Admin | 201 + `Location` |
| `PATCH /api/teams/{id}` `{name?, description?}` | Admin | 200 |
| `DELETE /api/teams/{id}` | Admin | 204 (soft delete with its memberships) |
| `GET /api/teams/{id}/members` | Admin, or a member of the team | 200, paged |
| `GET /api/teams/{id}/members/{userId}` | Admin, or a member of the team | 200 (the `Location` target of the add call) |
| `PUT /api/teams/{id}/members/{userId}` `{teamRole}` | Admin; Manager who leads the team (Member accounts only) | 201 added / 200 role set |
| `DELETE /api/teams/{id}/members/{userId}` | same | 204 |
| `GET /api/audit` | Admin | 200, paged |

`q` matches display name and email (users) or the name (teams), case-insensitively and literally (`%` and `_` are not wildcards). A deleted user or team disappears from lists and returns 404. List reads use a constant number of queries (no N+1), asserted by tests for users, teams and members.

## Admin web pages

Server-rendered (Thymeleaf + HTMX, no JavaScript framework). All pages put the CSRF token on `<body hx-headers=...>`, every POST needs it, and every action is authorized again in the service layer. The navigation only shows what the role may use.

| Page | Who | What |
|------|-----|------|
| `/admin/users` | Admin, Manager | Paged table with search and role / enabled filters; row actions per the matrix above (enable / disable, set role and delete for Admin only); invite form; pending invitations with revoke. The invitation link is shown **once**, in the response to the invite request (`Cache-Control: no-store`); it is never put in the session, a flash attribute or the log, and a reload shows nothing |
| `/admin/teams` | everyone (filtered) | Team list and search; create form for Admin |
| `/admin/teams/{id}` | Admin, members of the team | Members table, add member with a searchable user select, change team role, remove; rename and delete for Admin |
| `/admin/audit` | Admin | Paged audit table with actor, action, target type and date range filters |

Forms and rows update through HTMX fragments; validation errors come back inline (HTTP 422 / 409) and rule violations as a short message (status kept). Plain requests get a friendly error page (403 / 404) with no stack trace.

## 403 versus 404

- A caller who lacks the permission, or who tries something outside their scope (a MEMBER asking for another user, a MANAGER acting on an ADMIN, a Manager changing the members of a team they do not lead, revoking someone else's invitation), gets **403**. Role-level answers do not depend on whether the target exists.
- **404** is returned only to callers who are allowed to use the endpoint, and only for ids that do not exist (or are deleted, or for an invitation that is no longer pending).
- **Teams follow the same rule, without a probing hole**: a caller who may not see all teams (everyone except Admin) gets **403 `E018` for any team they do not belong to, whether or not it exists**, so team ids cannot be enumerated (unlike users, plain members cannot list all teams). Only an Admin sees 404 `E019` for an unknown team.
- Why 403 and not 404 for out-of-scope user targets: user ids are sequential and a Manager may list every user anyway, so hiding existence would add confusion and protect nothing. The self-service endpoints never take an id, so a user cannot probe others through them.
- Unauthenticated requests are 401 (API) or a redirect to `/login` (web). Invitation tokens are different: every unusable token gets the same 400 (`E016`), never a 404 or 403, so nothing reveals why.

## Last-admin guard

The last **enabled** Admin can never be demoted, disabled or deleted (409, `E014`). Self-actions are already blocked, so the guard matters when two Admins act on each other at the same moment. Every role, enabled and delete change first locks *all* enabled Admin rows (`SELECT ... FOR UPDATE`, in id order, so there is no deadlock) and then re-checks the count, so two concurrent requests cannot both succeed (tested with two Admins demoting each other in parallel).

## Access changes take effect at once

Changing a role, disabling, enabling or deleting a user:

- sets `users.access_changed_at` (access tokens issued before it and older web sessions are rejected, see [auth.md](auth.md));
- revokes all of the user's refresh tokens;
- is audited in the same transaction.

A demoted Admin loses admin pages on the very next request, and their existing API tokens stop working. They have to sign in again (a sign-in inside the same clock second as the change is rejected and must be repeated).

Deleting a user also revokes the pending invitations they created. Deleted users keep their row (audit history stays intact); only the email becomes reusable.

**Team changes.** The principal (with its memberships) is rebuilt from the database on every request, so a membership or team role change applies to the very next request **without** cutting the session. `access_changed_at` is bumped (same cut-off as above, via `AccessRevocationService`) only when someone **loses management rights**:

| Change | Cut-off |
|--------|---------|
| Added to a team, promoted to LEAD, role set to the value it already has | no |
| Removed from a team as a plain member | no |
| Demoted from LEAD to MEMBER | **yes** |
| Removed from a team as LEAD | **yes** |
| A team is deleted | **yes** for every LEAD of that team; plain members are not cut off |

The rule looks at the team role only, so the cut-off also applies to a LEAD whose account role is MEMBER (who has no management rights anyway): it is a conservative reset, never a missing one. A user who is disabled or deleted is cut off by the existing user rules.

## Invitation flow

No email is sent. The inviter receives the link once and passes it on.

1. `POST /api/invitations {email, role}` (Admin: any role, Manager: Member only). Response `201` with `Location` (`GET /api/invitations/{id}`) and `acceptUrl = APP_BASE_URL + /invite/<token>`. The token is 256 random bits, URL-safe (43 characters). **Only its SHA-256 is stored**, so the link cannot be shown again; if it is lost, create a new invitation.
2. TTL is 7 days (`INVITATION_TTL`). Creating a new invitation for the same email revokes the previous open one (one pending invitation per email, enforced by a partial unique index). A Manager cannot replace a live invitation created by someone else (409, `E017`); an Admin can. An email that belongs to an existing live user returns 409 (`E002`).
3. The invitee opens `/invite/<token>` (or calls `POST /api/invitations/accept`), chooses a display name and a password (12 to 128 characters, not equal to the email) and the account is created with exactly the invited role. The page has CSRF protection and the `hx-headers` token on `<body>`, is sent with `Cache-Control: no-store` and `Referrer-Policy: no-referrer` (the token is in the URL), and is not affected by the first-run setup redirect.
4. A link works once. Unknown, expired, used, revoked and replaced tokens all produce the same error (`E016`, HTTP 400 on the API, the same generic page on the web), whatever the reason.
5. The invitee then signs in normally at `/login` or `POST /api/auth/login`.

Operations notes: set `APP_BASE_URL` to the public HTTPS address, otherwise the generated links point to the wrong host. Treat the link like a password until it is used. Per-IP rate limiting of the public accept endpoint belongs at the reverse proxy (see [auth.md](auth.md)).

## Audit

`GET /api/audit` (Admin only) returns all entries newest first: `id`, `createdOn`, `action`, `subAction`, `actorId`, `actorName`, `targetType` (`USER`, `INVITATION` or `TEAM`), `targetId`, `relatedUserId`. Filters: `actorId`, `action`, `targetType`, `targetId`, `from`, `to` (ISO-8601 instants, inclusive; `from` after `to` is a 400), `page`, `size` (max 100). `actorName` is `null` when the actor was deleted later; the entry stays. Administrative actions are `USER_UPDATED`, `USER_ROLE_CHANGED`, `USER_DISABLED`, `USER_ENABLED`, `USER_DELETED`, `INVITATION_CREATED`, `INVITATION_REVOKED` (also written when an invitation is replaced), `INVITATION_ACCEPTED` (actor = the new user), and for teams `TEAM_CREATED`, `TEAM_UPDATED`, `TEAM_DELETED`, `TEAM_MEMBER_ADDED`, `TEAM_MEMBER_REMOVED`, `TEAM_MEMBER_ROLE_CHANGED`.

Team entries have target type `TEAM` and the team id as `targetId`. For the three member actions, **`relatedUserId` holds the affected user** (a plain id, no foreign key, `null` for every other action); the new team role is not stored. Deleting a team writes one `TEAM_DELETED` entry, not one per membership. Entries are written in the same transaction as the change; refused actions and no-op changes are not logged. `GET /api/users/me/activity` is unchanged and shows a user only their own entries. Tokens, passwords, request bodies and team names are never logged.

## Error codes

| Code | HTTP | When |
|------|------|------|
| `E004` | 403 | Missing permission (also an unlisted route for non-admins) |
| `E013` | 403 | Allowed endpoint, but not on this user (Manager on Admin/Manager, role change by a non-Admin, invitation of someone else, another user's teams) |
| `E015` | 403 | Changing your own role, disabling or deleting yourself |
| `E018` | 403 | Not allowed on this team (not your team, or not a lead of it); also for a team id that does not exist when the caller is not an Admin |
| `E014` | 409 | Would remove the last enabled Admin |
| `E002` | 409 | Invitation for an email that already has a live account |
| `E017` | 409 | Manager tries to replace a live invitation made by someone else |
| `E020` | 409 | A live team already has this name (ignoring case) |
| `E022` | 409 | A disabled user cannot be added to a team |
| `E016` | 400 | Invitation token invalid, expired, used, revoked or replaced |
| `E001` | 404 | Unknown (or deleted) user, or invitation that is not pending |
| `E019` | 404 | Unknown or deleted team (Admin only; others get `E018`) |
| `E021` | 404 | The user is not a member of the team |
| `E005` | 400 | Validation (including an empty `PATCH`, `size` above 100, `from` after `to`) |

The full list is in [auth.md](auth.md#error-codes).
