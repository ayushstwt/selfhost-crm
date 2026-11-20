package com.ayshriv.selfhostcrm.config;

import java.util.Locale;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup in the prod profile when the database password is blank, too short or a well-known
 * placeholder. The value itself is never included in the error message.
 */
@Component
@Profile("prod")
public class ProdDatabasePasswordValidator {

	static final int MIN_LENGTH = 12;

	private static final Set<String> FORBIDDEN = Set.of("change-me", "selfhostcrm", "postgres", "password");

	public ProdDatabasePasswordValidator(Environment environment) {
		validate(environment.getProperty("spring.datasource.password"));
	}

	static void validate(String password) {
		if (password == null || password.isBlank()) {
			throw new IllegalStateException("DB_PASSWORD is not set. Set it to a random value of at least "
					+ MIN_LENGTH + " characters (for example: openssl rand -base64 24).");
		}
		if (password.length() < MIN_LENGTH) {
			throw new IllegalStateException("DB_PASSWORD is shorter than " + MIN_LENGTH + " characters.");
		}
		if (FORBIDDEN.contains(password.strip().toLowerCase(Locale.ROOT))) {
			throw new IllegalStateException("DB_PASSWORD is a well-known default value. Choose a random password.");
		}
	}

}
