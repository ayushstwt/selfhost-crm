package com.ayshriv.selfhostcrm.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ayshriv.selfhostcrm.config.security.JwtConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

class JwtSecretValidationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withUserConfiguration(JwtConfig.class);

	private final ApplicationContextRunner prod = runner.withPropertyValues("spring.profiles.active=prod");

	private final ApplicationContextRunner dev = runner.withPropertyValues("spring.profiles.active=dev");

	private static String random32() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	@Test
	void prodProfileFailsWhenTheSecretIsMissing() {
		prod.run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
				.hasMessageContaining("JWT_SECRET is not set"));
	}

	@Test
	void prodProfileFailsWhenTheSecretIsShorterThan32Bytes() {
		prod.withPropertyValues("app.security.jwt.secret=too-short").run(ctx -> assertThat(ctx).hasFailed()
				.getFailure().rootCause().hasMessageContaining("shorter than 32 bytes"));
	}

	@Test
	void prodProfileStartsWithA32ByteSecret() {
		prod.withPropertyValues("app.security.jwt.secret=" + random32())
				.run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(JwtEncoder.class));
	}

	@Test
	void devProfileGeneratesARandomSecretWhenNoneIsSet() {
		dev.run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(JwtEncoder.class)
				.hasSingleBean(JwtDecoder.class));
	}

	@Test
	void devProfileStillRejectsASecretThatIsSetButTooShort() {
		dev.withPropertyValues("app.security.jwt.secret=too-short").run(ctx -> assertThat(ctx).hasFailed()
				.getFailure().rootCause().hasMessageContaining("shorter than 32 bytes"));
	}

	@Test
	void eachDevRunGetsADifferentSecret() {
		String[] tokens = new String[2];
		for (int i = 0; i < 2; i++) {
			int n = i;
			dev.run(ctx -> {
				var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().subject("1")
						.expiresAt(java.time.Instant.now().plusSeconds(60)).build();
				tokens[n] = ctx.getBean(JwtEncoder.class).encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters
						.from(org.springframework.security.oauth2.jwt.JwsHeader
								.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims))
						.getTokenValue();
			});
		}
		// a token minted by run 1 must not verify under run 2's key
		dev.run(ctx -> org.assertj.core.api.Assertions.assertThatThrownBy(() -> ctx.getBean(JwtDecoder.class)
				.decode(tokens[0])).isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class));
	}

}
