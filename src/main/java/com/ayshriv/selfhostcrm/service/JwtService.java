package com.ayshriv.selfhostcrm.service;

import com.ayshriv.selfhostcrm.config.security.JwtProperties;
import com.ayshriv.selfhostcrm.domain.Role;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Mints short-lived HS256 access tokens (subject = user id, plus email and role). */
@Service
public class JwtService {

	public record AccessToken(String value, long expiresInSeconds) {
	}

	private final JwtEncoder encoder;

	private final JwtProperties properties;

	public JwtService(JwtEncoder encoder, JwtProperties properties) {
		this.encoder = encoder;
		this.properties = properties;
	}

	public AccessToken issueAccessToken(Long userId, String email, Role role) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.subject(String.valueOf(userId))
				.claim("email", email)
				.claim("role", role.name())
				.issuedAt(now)
				.expiresAt(now.plus(properties.accessTokenTtl()))
				.id(UUID.randomUUID().toString())
				.build();
		String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		return new AccessToken(token, properties.accessTokenTtl().toSeconds());
	}

}
