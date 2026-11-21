package com.ayshriv.selfhostcrm.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AcceptInvitationRequest(
		@NotBlank(message = "Token is required") @Size(max = 128, message = "Token is too long") String token,
		@NotBlank(message = "Display name is required") @Size(max = 100, message = "Display name is too long") String displayName,
		@NotBlank(message = "Password is required")
		@Size(min = 12, max = 128, message = "Password must be 12 to 128 characters") String password) {
}
