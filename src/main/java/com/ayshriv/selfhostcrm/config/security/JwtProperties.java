package com.ayshriv.selfhostcrm.config.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** JWT settings. The secret comes from the JWT_SECRET environment variable (HS256, at least 32 bytes). */
@ConfigurationProperties("app.security.jwt")
public record JwtProperties(
		@DefaultValue("") String secret,
		@DefaultValue("15m") Duration accessTokenTtl,
		@DefaultValue("7d") Duration refreshTokenTtl) {

	public static final int MIN_SECRET_BYTES = 32;

	/** Fails startup when the secret is missing or too short. */
	public static void validateSecret(String secret) {
		if (secret == null || secret.isBlank()) {
			throw new IllegalStateException("JWT_SECRET is not set. Set it to a random value of at least "
					+ MIN_SECRET_BYTES + " bytes (for example: openssl rand -base64 48).");
		}
		if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
			throw new IllegalStateException("JWT_SECRET is shorter than " + MIN_SECRET_BYTES + " bytes.");
		}
	}

}
