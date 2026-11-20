package com.ayshriv.selfhostcrm.web;

import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import java.time.Instant;

/** Public view of a user. Deliberately has no password hash and no internal flags. */
public record UserResponse(Long id, String email, String displayName, Role role, boolean enabled,
		Instant lastLoginAt, Instant createdOn) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
				user.isEnabled(), user.getLastLoginAt(), user.getCreatedOn());
	}

}
