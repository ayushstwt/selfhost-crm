package com.ayshriv.selfhostcrm.web;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Partial update: send displayName, enabled, or both. */
public record UpdateUserRequest(
		@Size(max = 100, message = "Display name is too long")
		@Pattern(regexp = ".*\\S.*", message = "Display name must not be blank") String displayName,
		Boolean enabled) {

	@AssertTrue(message = "Send displayName, enabled or both")
	public boolean isAnyFieldPresent() {
		return displayName != null || enabled != null;
	}

}
