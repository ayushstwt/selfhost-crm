package com.ayshriv.selfhostcrm.web;

/** Returned once by login and refresh. {@code expiresIn} is the access token lifetime in seconds. */
public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {
}
