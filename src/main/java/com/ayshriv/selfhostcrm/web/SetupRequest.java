package com.ayshriv.selfhostcrm.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Form for creating the first Admin. */
public record SetupRequest(
		@NotBlank(message = "Display name is required") @Size(max = 100, message = "Display name is too long") String displayName,
		@NotBlank(message = "Email is required") @Email(message = "Enter a valid email address") @Size(max = 254) String email,
		@NotBlank(message = "Password is required") @Size(min = 12, max = 128, message = "Password must be 12 to 128 characters") String password,
		String confirmPassword) {
}
