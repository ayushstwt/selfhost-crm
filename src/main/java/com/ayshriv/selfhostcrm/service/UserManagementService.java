package com.ayshriv.selfhostcrm.service;

import com.ayshriv.selfhostcrm.config.security.UserAccessPolicy;
import com.ayshriv.selfhostcrm.config.security.UserPrincipal;
import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import com.ayshriv.selfhostcrm.domain.AuditTargetType;
import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.domain.UserAction;
import com.ayshriv.selfhostcrm.exception.ResourceNotFoundException;
import com.ayshriv.selfhostcrm.repository.InvitationRepository;
import com.ayshriv.selfhostcrm.repository.UserRepository;
import com.ayshriv.selfhostcrm.repository.UserSpecifications;
import com.ayshriv.selfhostcrm.web.UpdateUserRequest;
import com.ayshriv.selfhostcrm.web.UserResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User administration. Permission checks are {@code @PreAuthorize} on each method; who may act on whom is
 * {@link UserAccessPolicy}. Every change and its audit row share one transaction. Role, enabled and delete
 * changes take the "all enabled admins" lock first (the last-admin guard) and then cut off the user's
 * existing access via {@link AccessRevocationService}.
 */
@Service
public class UserManagementService {

	private final UserRepository users;

	private final UserAccessPolicy policy;

	private final AccessRevocationService revocation;

	private final UserLogService userLog;

	private final InvitationRepository invitations;

	public UserManagementService(UserRepository users, UserAccessPolicy policy,
			AccessRevocationService revocation, UserLogService userLog, InvitationRepository invitations) {
		this.invitations = invitations;
		this.users = users;
		this.policy = policy;
		this.revocation = revocation;
		this.userLog = userLog;
	}

	@PreAuthorize("hasAuthority('USER_VIEW')")
	@Transactional(readOnly = true)
	public Page<UserResponse> list(Role role, Boolean enabled, String search, int page, int size) {
		PageRequest pageable = PageRequest.of(page, size,
				Sort.by(Sort.Order.desc("createdOn"), Sort.Order.desc("id")));
		return users.findAll(UserSpecifications.filter(role, enabled, search), pageable).map(UserResponse::from);
	}

	@PreAuthorize("hasAuthority('USER_VIEW')")
	@Transactional(readOnly = true)
	public UserResponse get(Long id) {
		return users.findById(id).map(UserResponse::from)
				.orElseThrow(() -> new ResourceNotFoundException(ErrorMessages.E001));
	}

	@PreAuthorize("hasAuthority('USER_UPDATE')")
	@Transactional
	public UserResponse update(UserPrincipal actor, Long id, UpdateUserRequest request) {
		boolean touchesEnabled = request.enabled() != null;
		List<User> admins = touchesEnabled ? users.lockEnabledAdmins() : List.of();
		User target = load(id);
		policy.assertCanUpdate(actor, target, touchesEnabled);

		boolean nameChanged = request.displayName() != null && !request.displayName().trim().equals(target.getDisplayName());
		boolean enabledChanged = touchesEnabled && request.enabled() != target.isEnabled();
		if (enabledChanged && !request.enabled()) {
			policy.assertNotLastAdmin(target, admins);
		}
		if (nameChanged) {
			target.setDisplayName(request.displayName().trim());
			userLog.recordAction(actor.getId(), UserAction.USER_UPDATED, AuditTargetType.USER, id);
		}
		if (enabledChanged) {
			target.setEnabled(request.enabled());
			userLog.recordAction(actor.getId(), request.enabled() ? UserAction.USER_ENABLED
					: UserAction.USER_DISABLED, AuditTargetType.USER, id);
			revocation.revokeAccess(target); // last: flushes, then clears the persistence context
		}
		return UserResponse.from(target);
	}

	@PreAuthorize("hasAuthority('USER_ROLE_CHANGE')")
	@Transactional
	public UserResponse changeRole(UserPrincipal actor, Long id, Role newRole) {
		List<User> admins = users.lockEnabledAdmins();
		User target = load(id);
		policy.assertCanChangeRole(actor, target);
		if (target.getRole() != newRole) {
			if (newRole != Role.ADMIN) {
				policy.assertNotLastAdmin(target, admins);
			}
			target.setRole(newRole);
			userLog.recordAction(actor.getId(), UserAction.USER_ROLE_CHANGED, AuditTargetType.USER, id);
			revocation.revokeAccess(target);
		}
		return UserResponse.from(target);
	}

	/** Soft delete. The email becomes reusable (unique only among live users). */
	@PreAuthorize("hasAuthority('USER_DELETE')")
	@Transactional
	public void delete(UserPrincipal actor, Long id) {
		List<User> admins = users.lockEnabledAdmins();
		User target = load(id);
		policy.assertCanDelete(actor, target);
		policy.assertNotLastAdmin(target, admins);
		// A deleted user's outstanding invitation links stop working (bulk update, does not clear the context).
		invitations.revokePendingByInviter(id, Instant.now());
		target.setDeleted(true);
		target.setEnabled(false);
		userLog.recordAction(actor.getId(), UserAction.USER_DELETED, AuditTargetType.USER, id);
		revocation.revokeAccess(target);
	}

	private User load(Long id) {
		return users.findForUpdateById(id).orElseThrow(() -> new ResourceNotFoundException(ErrorMessages.E001));
	}

}
