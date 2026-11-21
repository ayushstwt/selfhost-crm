package com.ayshriv.selfhostcrm.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProdAppBaseUrlValidationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(Props.class, ProdAppBaseUrlValidator.class);

	@EnableConfigurationProperties(AppProperties.class)
	static class Props {

	}

	private final ApplicationContextRunner prod = runner.withPropertyValues("spring.profiles.active=prod");

	@Test
	void prodFailsWhenMissing() {
		prod.run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
				.hasMessageContaining("APP_BASE_URL is not set"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "crm.example.com", "/relative", "ftp://crm.example.com", "javascript:alert(1)",
			"https://", "https://crm.example.com?x=1", "https://user:pw@crm.example.com", "https://a.com#frag" })
	void prodRejectsNonAbsoluteHttpUrls(String value) {
		prod.withPropertyValues("app.base-url=" + value).run(ctx -> assertThat(ctx).hasFailed());
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://crm.example.com", "http://localhost:8080", "https://crm.example.com/crm/" })
	void prodAcceptsAbsoluteHttpUrls(String value) {
		prod.withPropertyValues("app.base-url=" + value).run(ctx -> assertThat(ctx).hasNotFailed());
	}

	@Test
	void trailingSlashesAreTrimmed() {
		assertThat(new AppProperties("https://crm.example.com//", null).normalizedBaseUrl())
				.isEqualTo("https://crm.example.com");
	}

	@Test
	void notEnforcedOutsideProd() {
		runner.withPropertyValues("spring.profiles.active=dev").run(ctx -> assertThat(ctx).hasNotFailed());
	}

}
