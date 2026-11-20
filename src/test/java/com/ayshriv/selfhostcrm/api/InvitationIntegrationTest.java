package com.ayshriv.selfhostcrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.service.TokenHasher;
import com.ayshriv.selfhostcrm.support.ApiTestSupport;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MvcResult;

class InvitationIntegrationTest extends ApiTestSupport {

	private static final String BASE = "http://localhost:8080/invite/";

	private record Created(long id, String token, String url, MvcResult result) {
	}

	private Created invite(String actorToken, String email, Role role) throws Exception {
		MvcResult r = mvc.perform(bearer(json(post("/api/invitations"),
				"{\"email\":\"%s\",\"role\":\"%s\"}".formatted(email, role)), actorToken)).andReturn();
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
		String url = JsonPath.read(r.getResponse().getContentAsString(), "$.data.acceptUrl");
		long id = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data.id")).longValue();
		return new Created(id, url.substring(url.lastIndexOf('/') + 1), url, r);
	}

	private MvcResult accept(String token, String name, String password) throws Exception {
		return mvc.perform(json(post("/api/invitations/accept"),
				"{\"token\":\"%s\",\"displayName\":\"%s\",\"password\":\"%s\"}".formatted(token, name, password)))
				.andReturn();
	}

	private void assertGenericInvalid(MvcResult r) throws Exception {
		assertThat(r.getResponse().getStatus()).isEqualTo(400);
		assertThat(field(r, "$.errorCode")).isEqualTo("E016");
		assertThat(field(r, "$.message")).isEqualTo("This invitation link is invalid or has expired");
	}

	private void seed() {
		createUser("admin@example.com", Role.ADMIN);
		createUser("manager@example.com", Role.MANAGER);
		createUser("member@example.com", Role.MEMBER);
	}

	// ---- role x endpoint matrix

	private record Row(String name, int anonymous, int member, int manager, int admin) {

		@Override
		public String toString() {
			return name;
		}

	}

	static Stream<Arguments> matrix() {
		return Stream.of(
				new Row("create invitation", 401, 403, 201, 201),
				new Row("list invitations", 401, 403, 200, 200),
				new Row("revoke someone else's invitation", 401, 403, 403, 204),
				new Row("accept with an unknown token", 400, 400, 400, 400)).map(Arguments::of);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("matrix")
	void roleMatrix(Row row) throws Exception {
		seed();
		String adminToken = login("admin@example.com");
		String[] actors = { null, login("member@example.com"), login("manager@example.com"), adminToken };
		int[] expected = { row.anonymous(), row.member(), row.manager(), row.admin() };
		for (int i = 0; i < actors.length; i++) {
			jdbc.execute("delete from invitations");
			Created adminInvite = invite(adminToken, "someone-else@example.com", Role.MEMBER);
			var request = switch (row.name()) {
				case "create invitation" -> json(post("/api/invitations"),
						"{\"email\":\"fresh" + i + "@example.com\",\"role\":\"MEMBER\"}");
				case "list invitations" -> get("/api/invitations");
				case "revoke someone else's invitation" -> delete("/api/invitations/" + adminInvite.id());
				default -> json(post("/api/invitations/accept"),
						"{\"token\":\"nope\",\"displayName\":\"x\",\"password\":\"" + PASSWORD + "\"}");
			};
			int status = mvc.perform(bearer(request, actors[i])).andReturn().getResponse().getStatus();
			assertThat(status).as("%s as actor #%d", row.name(), i).isEqualTo(expected[i]);
		}
	}

	// ---- creating

	@Test
	void managerInvitesMemberOnlyAdminInvitesAnyRole() throws Exception {
		seed();
		String manager = login("manager@example.com");
		for (Role role : List.of(Role.MANAGER, Role.ADMIN)) {
			mvc.perform(bearer(json(post("/api/invitations"),
					"{\"email\":\"x@example.com\",\"role\":\"" + role + "\"}"), manager))
					.andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("E013"));
		}
		invite(manager, "ok@example.com", Role.MEMBER);
		String admin = login("admin@example.com");
		invite(admin, "newadmin@example.com", Role.ADMIN);
		invite(admin, "newmanager@example.com", Role.MANAGER);
	}

	@Test
	void createReturns201WithLocationOneTimeUrlAndStoresOnlyTheHash() throws Exception {
		seed();
		Created c = invite(login("admin@example.com"), "New.Hire@Example.com", Role.MEMBER);
		assertThat(c.url()).startsWith(BASE);
		assertThat(c.token()).hasSize(43).matches("[A-Za-z0-9_-]+");
		assertThat(c.result().getResponse().getHeader("Location")).endsWith("/api/invitations/" + c.id());
		assertThat(field(c.result(), "$.data.email")).isEqualTo("new.hire@example.com");

		var row = jdbc.queryForMap("select * from invitations where id = ?", c.id());
		assertThat(row.get("token_hash")).isEqualTo(TokenHasher.hash(c.token()));
		assertThat(row.values().stream().map(String::valueOf)).noneMatch(v -> v.contains(c.token()));
		// 7 day default TTL
		long hours = jdbc.queryForObject("select extract(epoch from expires_at - created_on) / 3600 from invitations "
				+ "where id = ?", Double.class, c.id()).longValue();
		assertThat(hours).isBetween(167L, 168L);

		// the list never shows the token or hash
		String list = mvc.perform(bearer(get("/api/invitations"), login("admin@example.com"))).andReturn()
				.getResponse().getContentAsString();
		assertThat(list).doesNotContain(c.token(), "tokenHash", "token_hash", "acceptUrl", "\"active\"", "\"deleted\"");
	}

	@Test
	void existingActiveEmailIs409() throws Exception {
		seed();
		mvc.perform(bearer(json(post("/api/invitations"), "{\"email\":\"Member@example.com\",\"role\":\"MEMBER\"}"),
				login("admin@example.com"))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value("E002"));
	}

	@Test
	void validationErrorsAre400() throws Exception {
		seed();
		String admin = login("admin@example.com");
		mvc.perform(bearer(json(post("/api/invitations"), "{\"email\":\"not-an-email\",\"role\":\"MEMBER\"}"), admin))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("E005"));
		mvc.perform(bearer(json(post("/api/invitations"), "{\"email\":\"a@example.com\"}"), admin))
				.andExpect(status().isBadRequest());
	}

	@Test
	void aNewInvitationReplacesThePreviousPendingOne() throws Exception {
		seed();
		String admin = login("admin@example.com");
		Created first = invite(admin, "dup@example.com", Role.MEMBER);
		Created second = invite(admin, "dup@example.com", Role.MANAGER);
		assertGenericInvalid(accept(first.token(), "Dup", PASSWORD));
		MvcResult ok = accept(second.token(), "Dup", PASSWORD);
		assertThat(ok.getResponse().getStatus()).isEqualTo(201);
		assertThat(field(ok, "$.data.role")).isEqualTo("MANAGER");
		assertThat(jdbc.queryForObject("select count(*) from invitations where email = 'dup@example.com' "
				+ "and revoked_at is null and accepted_at is null", Integer.class)).isZero();
	}

	@Test
	void managerCannotReplaceAnInvitationOfSomeoneElseButAdminCanReplaceManagers() throws Exception {
		seed();
		String admin = login("admin@example.com");
		String manager = login("manager@example.com");
		invite(admin, "shared@example.com", Role.MEMBER);
		mvc.perform(bearer(json(post("/api/invitations"), "{\"email\":\"shared@example.com\",\"role\":\"MEMBER\"}"),
				manager)).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("E017"));
		invite(manager, "mine@example.com", Role.MEMBER);
		invite(admin, "mine@example.com", Role.MEMBER);
		// a manager may replace their own
		invite(manager, "own@example.com", Role.MEMBER);
		invite(manager, "own@example.com", Role.MEMBER);
	}

	// ---- accepting

	@Test
	void acceptCreatesTheUserWithTheInvitedRoleAndTheLinkWorksOnce() throws Exception {
		seed();
		Created c = invite(login("manager@example.com"), "new@example.com", Role.MEMBER);
		MvcResult ok = accept(c.token(), "New Person", PASSWORD);
		assertThat(ok.getResponse().getStatus()).isEqualTo(201);
		assertThat(ok.getResponse().getHeader("Location")).contains("/api/users/");
		assertThat(field(ok, "$.data.role")).isEqualTo("MEMBER");
		assertThat(field(ok, "$.data.email")).isEqualTo("new@example.com");
		assertThat(ok.getResponse().getContentAsString()).doesNotContain("passwordHash", "\"active\"", "\"deleted\"");
		assertThat(loginResult("new@example.com").getResponse().getStatus()).isEqualTo(200);
		assertThat(jdbc.queryForObject("select accepted_user_id is not null from invitations where id = ?",
				Boolean.class, c.id())).isTrue();

		assertGenericInvalid(accept(c.token(), "Again", PASSWORD)); // single use
		assertThat(jdbc.queryForObject("select count(*) from users where email = 'new@example.com'", Integer.class))
				.isEqualTo(1);
	}

	@Test
	void everyInvalidCaseGivesTheSameError() throws Exception {
		seed();
		String admin = login("admin@example.com");
		Created expired = invite(admin, "expired@example.com", Role.MEMBER);
		jdbc.update("update invitations set expires_at = now() - interval '1 minute' where id = ?", expired.id());
		Created revoked = invite(admin, "revoked@example.com", Role.MEMBER);
		mvc.perform(bearer(delete("/api/invitations/" + revoked.id()), admin)).andExpect(status().isNoContent());
		Created used = invite(admin, "used@example.com", Role.MEMBER);
		assertThat(accept(used.token(), "U", PASSWORD).getResponse().getStatus()).isEqualTo(201);
		Created replacedFirst = invite(admin, "replaced@example.com", Role.MEMBER);
		invite(admin, "replaced@example.com", Role.MEMBER);

		List<MvcResult> results = List.of(accept("unknown-token", "X", PASSWORD), accept(expired.token(), "X", PASSWORD),
				accept(revoked.token(), "X", PASSWORD), accept(used.token(), "X", PASSWORD),
				accept(replacedFirst.token(), "X", PASSWORD));
		for (MvcResult r : results) {
			assertGenericInvalid(r);
		}
		assertThat(results.stream().map(r -> r.getResponse().getStatus()).distinct()).hasSize(1);
		assertThat(results.stream().map(r -> {
			try {
				return field(r, "$.message") + field(r, "$.errorCode");
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}).distinct()).hasSize(1);
	}

	@Test
	void acceptEnforcesThePasswordRules() throws Exception {
		seed();
		Created c = invite(login("admin@example.com"), "pw@example.com", Role.MEMBER);
		MvcResult tooShort = accept(c.token(), "P", "short");
		assertThat(tooShort.getResponse().getStatus()).isEqualTo(400);
		assertThat(field(tooShort, "$.errorCode")).isEqualTo("E005");
		MvcResult sameAsEmail = accept(c.token(), "P", "pw@example.com");
		assertThat(field(sameAsEmail, "$.errorCode")).isEqualTo("E011");
		// the invitation survives failed attempts
		assertThat(accept(c.token(), "P", PASSWORD).getResponse().getStatus()).isEqualTo(201);
	}

	@Test
	void concurrentAcceptsCreateExactlyOneUser() throws Exception {
		seed();
		Created c = invite(login("admin@example.com"), "race@example.com", Role.MEMBER);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();
			for (int i = 0; i < 4; i++) {
				Callable<Integer> task = () -> {
					start.await();
					return accept(c.token(), "R", PASSWORD).getResponse().getStatus();
				};
				futures.add(pool.submit(task));
			}
			start.countDown();
			List<Integer> statuses = new java.util.ArrayList<>();
			for (var f : futures) {
				statuses.add(f.get());
			}
			assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
			assertThat(statuses.stream().filter(s -> s == 400)).hasSize(3);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(jdbc.queryForObject("select count(*) from users where email = 'race@example.com'", Integer.class))
				.isEqualTo(1);
	}

	@Test
	void emailOfADeletedUserCanBeInvitedAgain() throws Exception {
		seed();
		String admin = login("admin@example.com");
		long memberId = users.findByEmail("member@example.com").orElseThrow().getId();
		mvc.perform(bearer(delete("/api/users/" + memberId), admin)).andExpect(status().isNoContent());
		Created c = invite(admin, "member@example.com", Role.MEMBER);
		assertThat(accept(c.token(), "Back", PASSWORD).getResponse().getStatus()).isEqualTo(201);
		assertThat(jdbc.queryForObject("select count(*) from users where email = 'member@example.com'", Integer.class))
				.isEqualTo(2); // one soft-deleted, one live
	}

	// ---- list and revoke

	@Test
	void listShowsPendingOnlyAndManagerSeesOnlyOwn() throws Exception {
		seed();
		String admin = login("admin@example.com");
		String manager = login("manager@example.com");
		invite(admin, "a1@example.com", Role.MEMBER);
		invite(manager, "m1@example.com", Role.MEMBER);
		Created expired = invite(manager, "m2@example.com", Role.MEMBER);
		jdbc.update("update invitations set expires_at = now() - interval '1 minute' where id = ?", expired.id());
		Created accepted = invite(manager, "m3@example.com", Role.MEMBER);
		accept(accepted.token(), "M", PASSWORD);

		mvc.perform(bearer(get("/api/invitations"), admin)).andExpect(status().isOk())
				.andExpect(jsonPath("$.metadata.totalItems").value(2));
		mvc.perform(bearer(get("/api/invitations"), manager)).andExpect(status().isOk())
				.andExpect(jsonPath("$.metadata.totalItems").value(1))
				.andExpect(jsonPath("$.data[0].email").value("m1@example.com"))
				.andExpect(jsonPath("$.data[0].invitedByName").value("manager"));
		mvc.perform(bearer(get("/api/invitations").param("size", "101"), admin)).andExpect(status().isBadRequest());
	}

	@Test
	void revokeRules() throws Exception {
		seed();
		String admin = login("admin@example.com");
		String manager = login("manager@example.com");
		Created adminsInvite = invite(admin, "a@example.com", Role.MEMBER);
		Created managersInvite = invite(manager, "m@example.com", Role.MEMBER);

		mvc.perform(bearer(delete("/api/invitations/" + adminsInvite.id()), manager)).andExpect(status().isForbidden());
		mvc.perform(bearer(delete("/api/invitations/" + managersInvite.id()), manager)).andExpect(status().isNoContent());
		mvc.perform(bearer(delete("/api/invitations/" + managersInvite.id()), manager)).andExpect(status().isNotFound());
		mvc.perform(bearer(delete("/api/invitations/999999"), admin)).andExpect(status().isNotFound());
		mvc.perform(bearer(delete("/api/invitations/" + adminsInvite.id()), admin)).andExpect(status().isNoContent());
		assertGenericInvalid(accept(adminsInvite.token(), "A", PASSWORD));
	}

	@Test
	void deletingAnInviterRevokesTheirPendingInvitations() throws Exception {
		seed();
		String admin = login("admin@example.com");
		Created c = invite(login("manager@example.com"), "pending@example.com", Role.MEMBER);
		long managerId = users.findByEmail("manager@example.com").orElseThrow().getId();
		mvc.perform(bearer(delete("/api/users/" + managerId), admin)).andExpect(status().isNoContent());
		assertGenericInvalid(accept(c.token(), "P", PASSWORD));
		// the list still renders (inviter row is soft-deleted)
		mvc.perform(bearer(get("/api/invitations"), admin)).andExpect(status().isOk());
	}

	// ---- web page

	@Test
	void webPageShowsFormWithCsrfAndNoReferrerAndAcceptsThePassword() throws Exception {
		seed();
		Created c = invite(login("admin@example.com"), "web@example.com", Role.MANAGER);
		mvc.perform(get("/invite/" + c.token())).andExpect(status().isOk())
				.andExpect(header().string("Referrer-Policy", "no-referrer"))
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("hx-headers")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("web@example.com")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("_csrf")));

		// CSRF is enforced
		mvc.perform(post("/invite/" + c.token()).param("displayName", "W").param("password", PASSWORD)
				.param("confirmPassword", PASSWORD)).andExpect(status().isForbidden());
		// mismatch re-renders the form
		mvc.perform(post("/invite/" + c.token()).with(csrf()).param("displayName", "W").param("password", PASSWORD)
				.param("confirmPassword", "different password")).andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Passwords do not match")));
		mvc.perform(post("/invite/" + c.token()).with(csrf()).param("displayName", "Web User")
				.param("password", PASSWORD).param("confirmPassword", PASSWORD))
				.andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?invited"));
		assertThat(users.findByEmail("web@example.com").orElseThrow().getRole()).isEqualTo(Role.MANAGER);
	}

	@Test
	void webPageShowsOneGenericErrorForEveryUnusableLink() throws Exception {
		seed();
		String admin = login("admin@example.com");
		Created used = invite(admin, "used@example.com", Role.MEMBER);
		accept(used.token(), "U", PASSWORD);
		Created revoked = invite(admin, "revoked@example.com", Role.MEMBER);
		mvc.perform(bearer(delete("/api/invitations/" + revoked.id()), admin));
		Created expired = invite(admin, "expired@example.com", Role.MEMBER);
		jdbc.update("update invitations set expires_at = now() - interval '1 minute' where id = ?", expired.id());

		String expectedBody = null;
		for (String token : List.of("bogus", used.token(), revoked.token(), expired.token())) {
			MvcResult r = mvc.perform(get("/invite/" + token)).andExpect(status().isBadRequest()).andReturn();
			String body = r.getResponse().getContentAsString();
			assertThat(body).contains("invalid or has expired");
			assertThat(body).doesNotContain("used", "revoked");
			// the CSRF token in hx-headers is different on every request
			String normalized = body.replaceAll("(?s)<input[^>]*>", "").replaceAll("hx-headers=\"[^\"]*\"", "");
			expectedBody = expectedBody == null ? normalized : expectedBody;
			assertThat(normalized).isEqualTo(expectedBody);
		}
		mvc.perform(post("/invite/bogus").with(csrf()).param("displayName", "x").param("password", PASSWORD)
				.param("confirmPassword", PASSWORD)).andExpect(status().isBadRequest());
	}

	@Test
	void invitePageIsReachableWithoutLoginAndWithoutSetupRedirect() throws Exception {
		// no users exist: the setup redirect must not swallow the invite route
		mvc.perform(get("/invite/anything")).andExpect(status().isBadRequest());
	}

}
