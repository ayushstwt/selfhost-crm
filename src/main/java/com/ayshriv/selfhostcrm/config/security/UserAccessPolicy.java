package com.ayshriv.selfhostcrm.config.security;

import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.exception.ForbiddenException;
import com.ayshriv.selfhostcrm.exception.ServiceException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Target-based rules: who may act on whom. Permissions (see {@code RolePermissions}) say what an actor may
 * do in general; this policy says whether they may do it to this particular user. Every method throws
 * (403 or 409) and returns normally when the action is allowed.
 *
 * <ul>
 * <li>ADMIN: any target, except the self-rules and the last-admin guard.</li>
 * <li>MANAGER: only MEMBER targets (plus their own display name); invites MEMBER only; never changes roles.</li>
 * <li>Nobody changes their own role or enabled state, or deletes themselves.</li>
 * <li>The last enabled ADMIN is never demoted, disabled or deleted.</li>
 * </ul>
 */
@Component
public class UserAccessPolicy {

	/** Display name and enabled state. {@code touchesEnabled} is true when the request changes enabled. */
	public void assertCanUpdate(UserPrincipal actor, User target, boolean touchesEnabled) {
		if (isSelf(actor, target)) {
			if (touchesEnabled) {
				throw new ForbiddenException(ErrorMessages.E015);
			}
			return; // own display name: ADMIN and MANAGER (MEMBER has no USER_UPDATE permission at all)
		}
		assertCanActOnOther(actor, target);
	}

	public void assertCanChangeRole(UserPrincipal actor, User target) {
		if (isSelf(actor, target)) {
			throw new ForbiddenException(ErrorMessages.E015);
		}
		if (actor.getRole() != Role.ADMIN) {
			throw new ForbiddenException(ErrorMessages.E013);
		}
	}

	public void assertCanDelete(UserPrincipal actor, User target) {
		if (isSelf(actor, target)) {
			throw new ForbiddenException(ErrorMessages.E015);
		}
		if (actor.getRole() != Role.ADMIN) {
			throw new ForbiddenException(ErrorMessages.E013);
		}
	}

	/** ADMIN may invite any role; MANAGER only MEMBER. */
	public void assertCanInvite(UserPrincipal actor, Role invitedRole) {
		if (actor.getRole() == Role.ADMIN) {
			return;
		}
		if (actor.getRole() == Role.MANAGER && invitedRole == Role.MEMBER) {
			return;
		}
		throw new ForbiddenException(ErrorMessages.E013);
	}

	/** ADMIN: any invitation. MANAGER: only invitations they created. */
	public void assertCanManageInvitation(UserPrincipal actor, Long invitedBy) {
		if (actor.getRole() == Role.ADMIN || (actor.getRole() == Role.MANAGER && actor.getId().equals(invitedBy))) {
			return;
		}
		throw new ForbiddenException(ErrorMessages.E013);
	}

	/**
	 * Call while holding the lock on all enabled admins ({@code UserRepository#lockEnabledAdmins}), before
	 * demoting, disabling or deleting {@code target}. {@code lockedEnabledAdmins} is that locked list.
	 */
	public void assertNotLastAdmin(User target, List<User> lockedEnabledAdmins) {
		boolean isEnabledAdmin = target.getRole() == Role.ADMIN && target.isEnabled();
		if (isEnabledAdmin && lockedEnabledAdmins.size() <= 1) {
			throw new ServiceException(ErrorMessages.E014);
		}
	}

	private void assertCanActOnOther(UserPrincipal actor, User target) {
		if (actor.getRole() == Role.ADMIN) {
			return;
		}
		if (actor.getRole() == Role.MANAGER && target.getRole() == Role.MEMBER) {
			return;
		}
		throw new ForbiddenException(ErrorMessages.E013);
	}

	private static boolean isSelf(UserPrincipal actor, User target) {
		return actor.getId().equals(target.getId());
	}

}
