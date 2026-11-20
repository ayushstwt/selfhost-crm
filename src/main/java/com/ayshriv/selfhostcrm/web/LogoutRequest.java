package com.ayshriv.selfhostcrm.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Body of POST /api/auth/logout: a refresh token in its URL-safe 43 character format. */
public record LogoutRequest(
		@NotBlank(message = "Refresh token is required")
		@Pattern(regexp = "^[A-Za-z0-9_-]{43}$", message = "Refresh token has an invalid format") String refreshToken) {
}
