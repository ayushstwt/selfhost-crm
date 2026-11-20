package com.ayshriv.selfhostcrm.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
		@NotBlank(message = "Current password is required") String currentPassword,
		@NotBlank(message = "New password is required") @Size(min = 12, max = 128, message = "Password must be 12 to 128 characters") String newPassword) {
}
