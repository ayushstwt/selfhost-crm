package com.ayshriv.selfhostcrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayshriv.selfhostcrm.TestcontainersConfiguration;
import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.security.lockout.max-attempts=3")
class ApiAuthIntegrationTest {

	private static final String EMAIL = "api@example.com";

	private static final String PASSWORD = "correct horse battery";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder encoder;

	@Autowired
	JwtEncoder jwtEncoder;

	@BeforeEach
	void seed() {
		jdbc.execute("delete from user_log");
		jdbc.execute("delete from refresh_tokens");
		jdbc.execute("delete from users");
		createUser(EMAIL, Role.MEMBER);
	}

	private User createUser(String email, Role role) {
		User u = new User();
		u.setEmail(email);
		u.setDisplayName("Api User");
		u.setPasswordHash(encoder.encode(PASSWORD));
		u.setRole(role);
		return users.saveAndFlush(u);
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
		return b.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private MvcResult login(String email, String password) throws Exception {
		return mvc.perform(json(post("/api/auth/login"),
				"{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))).andReturn();
	}

	private String field(MvcResult r, String path) throws Exception {
		return JsonPath.read(r.getResponse().getContentAsString(), path);
	}

	private MvcResult refresh(String token) throws Exception {
		return mvc.perform(json(post("/api/auth/refresh"), "{\"refreshToken\":\"%s\"}".formatted(token))).andReturn();
	}

	private long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	private String jwtFor(Instant issuedAt, Instant expiresAt, JwtEncoder encoder, Long userId) {
		JwtClaimsSet claims = JwtClaimsSet.builder().subject(String.valueOf(userId)).claim("email", EMAIL)
				.claim("role", "ADMIN").issuedAt(issuedAt).expiresAt(expiresAt).build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}

	private Long userId() {
		return jdbc.queryForObject("select id from users where email = ?", Long.class, EMAIL);
	}

	// ---- login -------------------------------------------------------------------------------

	@Test
	void loginReturnsTokensAndStoresOnlyTheRefreshHash() throws Exception {
		MvcResult r = login(EMAIL, PASSWORD);
		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		String refreshToken = field(r, "$.data.refreshToken");
		assertThat(field(r, "$.data.tokenType")).isEqualTo("Bearer");
		assertThat((Integer) JsonPath.read(r.getResponse().getContentAsString(), "$.data.expiresIn"))
				.isEqualTo(900);
		assertThat(refreshToken).hasSize(43).matches("[A-Za-z0-9_-]+");
		String stored = jdbc.queryForObject("select token_hash from refresh_tokens", String.class);
		assertThat(stored).hasSize(64).isNotEqualTo(refreshToken);
		assertThat(count("select count(*) from refresh_tokens where token_hash = ?", refreshToken)).isZero();
		assertThat(count("select count(*) from user_log where action = 'LOGIN' and sub_action = 'SUCCESS'"))
				.isEqualTo(1);
	}

	@Test
	void wrongPasswordAndUnknownEmailAreIndistinguishable() throws Exception {
		MvcResult wrong = login(EMAIL, "wrong password here");
		MvcResult unknown = login("nobody@example.com", "wrong password here");
		assertThat(wrong.getResponse().getStatus()).isEqualTo(401).isEqualTo(unknown.getResponse().getStatus());
		assertThat(field(wrong, "$.errorCode")).isEqualTo("E006").isEqualTo(field(unknown, "$.errorCode"));
		assertThat(field(wrong, "$.message")).isEqualTo(field(unknown, "$.message"));
		assertThat(count("select count(*) from user_log where action = 'LOGIN' and sub_action = 'FAILURE'"))
				.isEqualTo(1);
	}

	@Test
	void lockedAccountLooksExactlyLikeAWrongPassword() throws Exception {
		MvcResult wrong = login(EMAIL, "wrong password here");
		for (int i = 0; i < 2; i++) {
			login(EMAIL, "wrong password here");
		}
		assertThat(jdbc.queryForObject("select locked_until is not null from users", Boolean.class)).isTrue();
		MvcResult locked = login(EMAIL, PASSWORD);
		assertThat(locked.getResponse().getStatus()).isEqualTo(401).isEqualTo(wrong.getResponse().getStatus());
		assertThat(field(locked, "$.errorCode")).isEqualTo("E006").isEqualTo(field(wrong, "$.errorCode"));
		assertThat(field(locked, "$.message")).isEqualTo(field(wrong, "$.message"));
		// no token was issued
		assertThat(count("select count(*) from refresh_tokens")).isZero();
	}

	@Test
	void disabledUserCannotLogIn() throws Exception {
		jdbc.update("update users set enabled = false");
		MvcResult r = login(EMAIL, PASSWORD);
		assertThat(r.getResponse().getStatus()).isEqualTo(403);
		assertThat(field(r, "$.errorCode")).isEqualTo("E007");
		assertThat(login(EMAIL, "wrong password here").getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void loginValidationReturnsFieldErrors() throws Exception {
		mvc.perform(json(post("/api/auth/login"), "{\"email\":\"\",\"password\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("E005"))
				.andExpect(jsonPath("$.errors.length()").value(2));
	}

	// ---- protected endpoints ---------------------------------------------------------------

	private void assertUnauthorized(MockHttpServletRequestBuilder request) throws Exception {
		mvc.perform(request).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value("error"))
				.andExpect(jsonPath("$.errorCode").value("E003"))
				.andExpect(jsonPath("$.data").doesNotExist())
				.andExpect(jsonPath("$.path").value("/api/auth/me"))
				.andExpect(jsonPath("$.timestamp").exists());
	}

	@Test
	void noTokenMalformedExpiredAndTamperedTokensAreRejectedInTheEnvelope() throws Exception {
		assertUnauthorized(get("/api/auth/me"));
		assertUnauthorized(get("/api/auth/me").header("Authorization", "Bearer not-a-jwt"));
		assertUnauthorized(get("/api/auth/me").header("Authorization", "Basic abc"));

		Instant now = Instant.now();
		String expired = jwtFor(now.minusSeconds(1200), now.minusSeconds(600), jwtEncoder, userId());
		assertUnauthorized(get("/api/auth/me").header("Authorization", "Bearer " + expired));

		NimbusJwtEncoder otherKey = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
				java.util.UUID.randomUUID().toString().repeat(2).getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
		String forged = jwtFor(now, now.plusSeconds(600), otherKey, userId());
		assertUnauthorized(get("/api/auth/me").header("Authorization", "Bearer " + forged));

		String valid = jwtFor(now, now.plusSeconds(600), jwtEncoder, userId());
		String[] parts = valid.split("\\.");
		String badSignature = parts[0] + "." + parts[1] + "." + "A".repeat(parts[2].length());
		assertUnauthorized(get("/api/auth/me").header("Authorization", "Bearer " + badSignature));

		// sanity: the untampered one is accepted
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + valid)).andExpect(status().isOk());
	}

	@Test
	void tokenOfADisabledUserIsRejected() throws Exception {
		String access = field(login(EMAIL, PASSWORD), "$.data.accessToken");
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access)).andExpect(status().isOk());
		jdbc.update("update users set enabled = false");
		assertUnauthorized(get("/api/auth/me").header("Authorization", "Bearer " + access));
	}

	@Test
	void tokenOfADeletedUserIsRejected() throws Exception {
		String access = field(login(EMAIL, PASSWORD), "$.data.accessToken");
		jdbc.update("update users set is_deleted = true");
		assertUnauthorized(get("/api/auth/me").header("Authorization", "Bearer " + access));
	}

	@Test
	void accessDeniedIsWrittenInTheEnvelope() throws Exception {
		String member = field(login(EMAIL, PASSWORD), "$.data.accessToken");
		mvc.perform(get("/api/rbac-test/admin").header("Authorization", "Bearer " + member))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.status").value("error"))
				.andExpect(jsonPath("$.errorCode").value("E004"))
				.andExpect(jsonPath("$.path").value("/api/rbac-test/admin"));

		createUser("boss@example.com", Role.ADMIN);
		String admin = field(login("boss@example.com", PASSWORD), "$.data.accessToken");
		mvc.perform(get("/api/rbac-test/admin").header("Authorization", "Bearer " + admin))
				.andExpect(status().isOk());
	}

	@Test
	void meReturnsAUserDtoWithoutInternalFields() throws Exception {
		String access = field(login(EMAIL, PASSWORD), "$.data.accessToken");
		MvcResult r = mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value(EMAIL))
				.andExpect(jsonPath("$.data.role").value("MEMBER")).andReturn();
		assertNoSensitiveFields(r.getResponse().getContentAsString());
	}

	private void assertNoSensitiveFields(String body) {
		assertThat(body).doesNotContain("passwordHash", "password_hash", "tokenHash", "token_hash", "\"active\"",
				"\"deleted\"", "isActive", "isDeleted");
	}

	// ---- refresh -----------------------------------------------------------------------------

	@Test
	void refreshRotatesAndKeepsTheFamily() throws Exception {
		MvcResult first = login(EMAIL, PASSWORD);
		String oldToken = field(first, "$.data.refreshToken");
		MvcResult second = refresh(oldToken);
		assertThat(second.getResponse().getStatus()).isEqualTo(200);
		String newToken = field(second, "$.data.refreshToken");
		assertThat(newToken).isNotEqualTo(oldToken);
		assertNoSensitiveFields(second.getResponse().getContentAsString());

		assertThat(count("select count(distinct family_id) from refresh_tokens")).isEqualTo(1);
		assertThat(count("select count(*) from refresh_tokens where revoked_at is not null "
				+ "and replaced_by_id is not null")).isEqualTo(1);
		assertThat(count("select count(*) from refresh_tokens where revoked_at is null")).isEqualTo(1);
		// the new access token works
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + field(second, "$.data.accessToken")))
				.andExpect(status().isOk());
	}

	@Test
	void reusingARotatedTokenRevokesTheWholeFamily() throws Exception {
		String oldToken = field(login(EMAIL, PASSWORD), "$.data.refreshToken");
		String newToken = field(refresh(oldToken), "$.data.refreshToken");

		MvcResult reuse = refresh(oldToken);
		assertThat(reuse.getResponse().getStatus()).isEqualTo(401);
		assertThat(field(reuse, "$.errorCode")).isEqualTo("E010");
		assertThat(count("select count(*) from refresh_tokens where revoked_at is null")).isZero();
		assertThat(count("select count(*) from user_log where action = 'REFRESH_REUSE_DETECTED'")).isEqualTo(1);

		// the legitimate newer token is dead too
		assertThat(refresh(newToken).getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void unknownMalformedAndExpiredRefreshTokensGetTheSameGeneric401() throws Exception {
		String token = field(login(EMAIL, PASSWORD), "$.data.refreshToken");
		jdbc.update("update refresh_tokens set expires_at = ?", Timestamp.from(Instant.now().minusSeconds(60)));
		MvcResult expired = refresh(token);
		MvcResult unknown = refresh("x".repeat(43));
		MvcResult malformed = refresh("short");
		for (MvcResult r : List.of(expired, unknown, malformed)) {
			assertThat(r.getResponse().getStatus()).isEqualTo(401);
			assertThat(field(r, "$.errorCode")).isEqualTo("E010");
			assertThat(field(r, "$.message")).isEqualTo(field(expired, "$.message"));
		}
	}

	@Test
	void refreshOfADisabledUserFails() throws Exception {
		String token = field(login(EMAIL, PASSWORD), "$.data.refreshToken");
		jdbc.update("update users set enabled = false");
		assertThat(refresh(token).getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void concurrentRefreshWithTheSameTokenSucceedsOnlyOnce() throws Exception {
		String token = field(login(EMAIL, PASSWORD), "$.data.refreshToken");
		int threads = 6;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			results.add(pool.submit(() -> {
				go.await();
				return refresh(token).getResponse().getStatus();
			}));
		}
		go.countDown();
		int ok = 0;
		for (Future<Integer> f : results) {
			if (f.get() == 200) {
				ok++;
			}
		}
		pool.shutdown();
		assertThat(ok).isEqualTo(1);
	}

	// ---- logout ------------------------------------------------------------------------------

	@Test
	void logoutRevokesTheRefreshTokenAndIsIdempotent() throws Exception {
		String refreshToken = field(login(EMAIL, PASSWORD), "$.data.refreshToken");
		String body = "{\"refreshToken\":\"%s\"}".formatted(refreshToken);

		// public endpoint: no Authorization header needed
		for (int i = 0; i < 2; i++) {
			MvcResult out = mvc.perform(json(post("/api/auth/logout"), body)).andReturn();
			assertThat(out.getResponse().getStatus()).isEqualTo(204);
			assertThat(out.getResponse().getContentAsString()).isEmpty();
		}
		assertThat(count("select count(*) from refresh_tokens where revoked_at is null")).isZero();
		assertThat(refresh(refreshToken).getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void logoutNeverRevealsWhetherTheTokenExisted() throws Exception {
		String known = field(login(EMAIL, PASSWORD), "$.data.refreshToken");
		MvcResult existing = mvc.perform(json(post("/api/auth/logout"), "{\"refreshToken\":\"%s\"}".formatted(known)))
				.andReturn();
		MvcResult unknown = mvc.perform(json(post("/api/auth/logout"),
				"{\"refreshToken\":\"" + "a".repeat(43) + "\"}")).andReturn();
		assertThat(existing.getResponse().getStatus()).isEqualTo(204).isEqualTo(unknown.getResponse().getStatus());
		assertThat(existing.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
	}

	@Test
	void logoutIgnoresAStaleAuthorizationHeaderAndRejectsABadFormat() throws Exception {
		String token = field(login(EMAIL, PASSWORD), "$.data.refreshToken");
		mvc.perform(json(post("/api/auth/logout"), "{\"refreshToken\":\"%s\"}".formatted(token))
				.header("Authorization", "Bearer expired.or.garbage")).andExpect(status().isNoContent());
		mvc.perform(json(post("/api/auth/logout"), "{\"refreshToken\":\"bad token!\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("E005"));
	}

	// ---- change password -----------------------------------------------------------------------

	@Test
	void changePasswordRevokesAllRefreshTokensAndTheOldPasswordStopsWorking() throws Exception {
		MvcResult a = login(EMAIL, PASSWORD);
		String tokenB = field(login(EMAIL, PASSWORD), "$.data.refreshToken");
		String access = field(a, "$.data.accessToken");
		String change = "{\"currentPassword\":\"%s\",\"newPassword\":\"brand new password 1\"}".formatted(PASSWORD);

		mvc.perform(json(post("/api/auth/change-password"), change).header("Authorization", "Bearer " + access))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("success"));

		assertThat(count("select count(*) from refresh_tokens where revoked_at is null")).isZero();
		assertThat(refresh(tokenB).getResponse().getStatus()).isEqualTo(401);
		assertThat(refresh(field(a, "$.data.refreshToken")).getResponse().getStatus()).isEqualTo(401);
		assertThat(login(EMAIL, PASSWORD).getResponse().getStatus()).isEqualTo(401);
		assertThat(login(EMAIL, "brand new password 1").getResponse().getStatus()).isEqualTo(200);
		assertThat(count("select count(*) from user_log where action = 'PASSWORD_CHANGED' "
				+ "and sub_action = 'SUCCESS'")).isEqualTo(1);
	}

	@Test
	void changePasswordValidatesCurrentPasswordAndRules() throws Exception {
		String access = field(login(EMAIL, PASSWORD), "$.data.accessToken");
		mvc.perform(json(post("/api/auth/change-password"),
				"{\"currentPassword\":\"nope\",\"newPassword\":\"brand new password 1\"}")
				.header("Authorization", "Bearer " + access)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("E012"));
		mvc.perform(json(post("/api/auth/change-password"),
				"{\"currentPassword\":\"%s\",\"newPassword\":\"short\"}".formatted(PASSWORD))
				.header("Authorization", "Bearer " + access)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("E005"))
				.andExpect(jsonPath("$.errors[0].field").value("newPassword"));
		mvc.perform(json(post("/api/auth/change-password"),
				"{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(PASSWORD, EMAIL + "x"))
				.header("Authorization", "Bearer " + access)).andExpect(status().isOk());
		// password equal to the email is rejected
		String access2 = field(login(EMAIL, EMAIL + "x"), "$.data.accessToken");
		mvc.perform(json(post("/api/auth/change-password"),
				"{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(EMAIL + "x", EMAIL))
				.header("Authorization", "Bearer " + access2)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("E011"));
	}

	@Test
	void loginResponseNeverContainsSensitiveFields() throws Exception {
		assertNoSensitiveFields(login(EMAIL, PASSWORD).getResponse().getContentAsString());
	}

}
