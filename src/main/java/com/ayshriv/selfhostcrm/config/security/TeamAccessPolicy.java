package com.ayshriv.selfhostcrm.config.security;

import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import com.ayshriv.selfhostcrm.domain.Permission;
import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.RolePermissions;
import com.ayshriv.selfhostcrm.exception.ForbiddenException;
import org.springframework.stereotype.Component;

/**
 * Team rules: who may see a team, who may change its members, and whom they may add or remove. Permissions
 * ({@code TEAM_*}) gate the endpoints; this policy decides for a particular team and user. It reads the
 * caller's memberships from the {@link UserPrincipal} (no database access). Deny by default.
 *
 * <ul>
 * <li>See a team and its members: ADMIN (any team) or a member of that team.</li>
 * <li>Change members: ADMIN (any team) or a MANAGER who is LEAD of that team.</li>
 * <li>Target: ADMIN any user. MANAGER only users whose account role is MEMBER (never ADMIN or MANAGER
 * accounts, never themselves).</li>
 * </ul>
 * The assert methods throw {@code ForbiddenException} (403) and return normally when allowed. They are called
 * before the team is loaded, so a non-admin gets the same 403 for a team they are not in whether or not it exists.
 */
@Component
public class TeamAccessPolicy {

	public boolean canViewAllTeams(UserPrincipal actor) {
		return RolePermissions.has(actor.getRole(), Permission.TEAM_VIEW_ALL);
	}

	public boolean canView(UserPrincipal actor, Long teamId) {
		return canViewAllTeams(actor) || actor.isMemberOf(teamId);
	}

	public void assertCanView(UserPrincipal actor, Long teamId) {
		if (!canView(actor, teamId)) {
			throw new ForbiddenException(ErrorMessages.E018);
		}
	}

	/** May the actor change members of this team at all (before looking at who)? */
	public boolean canManageMembers(UserPrincipal actor, Long teamId) {
		if (!RolePermissions.has(actor.getRole(), Permission.TEAM_MEMBERS_MANAGE)) {
			return false;
		}
		return actor.getRole() == Role.ADMIN || (actor.getRole() == Role.MANAGER && actor.isLeadOf(teamId));
	}

	public void assertCanManageMembers(UserPrincipal actor, Long teamId) {
		if (!canManageMembers(actor, teamId)) {
			throw new ForbiddenException(ErrorMessages.E018);
		}
	}

	/** May the actor add, remove or change the team role of this user (given the account role)? */
	public boolean canChangeMember(UserPrincipal actor, Long targetUserId, Role targetAccountRole) {
		if (actor.getRole() == Role.ADMIN) {
			return true;
		}
		return actor.getRole() == Role.MANAGER && targetAccountRole == Role.MEMBER
				&& !actor.getId().equals(targetUserId);
	}

	public void assertCanChangeMember(UserPrincipal actor, Long targetUserId, Role targetAccountRole) {
		if (!canChangeMember(actor, targetUserId, targetAccountRole)) {
			throw new ForbiddenException(ErrorMessages.E013);
		}
	}

	/** {@code GET /api/users/{id}/teams}: ADMIN or the user themselves. */
	public void assertCanViewTeamsOfUser(UserPrincipal actor, Long userId) {
		if (!canViewAllTeams(actor) && !actor.getId().equals(userId)) {
			throw new ForbiddenException(ErrorMessages.E013);
		}
	}

}
