package com.ayshriv.selfhostcrm.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /api/auth/refresh. Any value that is not a live token gets the same generic 401. */
public record RefreshRequest(@NotBlank(message = "Refresh token is required") @Size(max = 512) String refreshToken) {
}
