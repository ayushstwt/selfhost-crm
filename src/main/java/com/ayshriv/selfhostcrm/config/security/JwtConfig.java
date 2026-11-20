package com.ayshriv.selfhostcrm.config.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * HS256 encoder/decoder (Nimbus) built from JWT_SECRET.
 * <ul>
 * <li>A secret that is set but shorter than 32 bytes always fails startup.</li>
 * <li>A missing secret fails startup, except in the dev profile where a random secret is generated for
 * this run (tokens stop working after a restart). No secret is ever hardcoded.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

	private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

	private final SecretKey key;

	public JwtConfig(JwtProperties properties, Environment environment) {
		String secret = properties.secret();
		if (secret == null || secret.isBlank()) {
			if (!environment.acceptsProfiles(Profiles.of("dev"))) {
				JwtProperties.validateSecret(secret); // throws: JWT_SECRET is not set
			}
			byte[] random = new byte[48];
			new SecureRandom().nextBytes(random);
			secret = Base64.getEncoder().encodeToString(random);
			log.warn("JWT_SECRET is not set: generated a random secret for the dev profile. "
					+ "All API tokens become invalid when the application restarts. Set JWT_SECRET to keep them.");
		}
		else {
			JwtProperties.validateSecret(secret);
		}
		this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder() {
		return new NimbusJwtEncoder(new ImmutableSecret<>(key));
	}

	@Bean
	JwtDecoder jwtDecoder() {
		return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
	}

}
