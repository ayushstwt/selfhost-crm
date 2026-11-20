package com.ayshriv.selfhostcrm.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProdDatabasePasswordValidationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(ProdDatabasePasswordValidator.class);

	private final ApplicationContextRunner prod = runner.withPropertyValues("spring.profiles.active=prod");

	@Test
	void prodFailsWhenThePasswordIsMissing() {
		prod.run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
				.hasMessageContaining("DB_PASSWORD is not set"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void prodFailsWhenThePasswordIsBlank(String blank) {
		prod.withPropertyValues("spring.datasource.password=" + blank).run(ctx -> assertThat(ctx).hasFailed()
				.getFailure().rootCause().hasMessageContaining("DB_PASSWORD is not set"));
	}

	@Test
	void prodFailsWhenThePasswordIsShorterThan12Characters() {
		prod.withPropertyValues("spring.datasource.password=elevenchars").run(ctx -> assertThat(ctx).hasFailed()
				.getFailure().rootCause().hasMessageContaining("shorter than 12"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "change-me", "selfhostcrm", "postgres", "password", "CHANGE-ME", "Postgres" })
	void prodFailsForWellKnownPasswords(String known) {
		prod.withPropertyValues("spring.datasource.password=" + known).run(ctx -> assertThat(ctx).hasFailed());
	}

	@Test
	void prodFailsForAWellKnownValueEvenIfPaddedToLength() {
		// the check is case-insensitive and ignores surrounding whitespace
		ProdDatabasePasswordValidatorAccess.assertRejected("  Selfhostcrm  ");
	}

	@Test
	void prodStartsWithARandomPasswordOf12OrMoreCharacters() {
		prod.withPropertyValues("spring.datasource.password=" + UUID.randomUUID())
				.run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(ProdDatabasePasswordValidator.class));
	}

	@Test
	void theCheckDoesNotApplyOutsideProd() {
		runner.withPropertyValues("spring.profiles.active=dev", "spring.datasource.password=short")
				.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(ProdDatabasePasswordValidator.class));
	}

	/** Same-package helper so the static check can be exercised directly. */
	private static final class ProdDatabasePasswordValidatorAccess {

		static void assertRejected(String password) {
			org.assertj.core.api.Assertions
					.assertThatThrownBy(() -> ProdDatabasePasswordValidator.validate(password))
					.isInstanceOf(IllegalStateException.class);
		}

	}

}
