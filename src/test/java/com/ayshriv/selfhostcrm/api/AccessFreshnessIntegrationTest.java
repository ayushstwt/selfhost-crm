package com.ayshriv.selfhostcrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

/** Role, disable and delete changes cut off access tokens, refresh tokens and web sessions at once. */
class AccessFreshnessIntegrationTest extends ApiTestSupport {

	private MockHttpSession webLogin(String email) throws Exception {
		MvcResult r = mvc.perform(post("/login").with(csrf()).param("username", email).param("password", PASSWORD))
				.andReturn();
		MockHttpSession session = (MockHttpSession) r.getRequest().getSession(false);
		assertThat(session).as("web login for %s", email).isNotNull();
		return session;
	}

	private int apiStatus(String token, String path) throws Exception {
		return mvc.perform(bearer(get(path), token)).andReturn().getResponse().getStatus();
	}

	private MvcResult refresh(String refreshToken) throws Exception {
		return mvc.perform(json(post("/api/auth/refresh"), "{\"refreshToken\":\"" + refreshToken + "\"}"))
				.andReturn();
	}

	private void assertSessionEnded(MockHttpSession session) throws Exception {
		MvcResult r = mvc.perform(get("/admin-test").session(session)).andReturn();
		assertThat(r.getResponse().getStatus()).isEqualTo(302);
		assertThat(r.getResponse().getRedirectedUrl()).endsWith("/login");
	}

	@Test
	void demotionCutsOffTokensRefreshTokensAndWebSessionsImmediately() throws Exception {
		createUser("a@example.com", Role.ADMIN);
		User b = createUser("b@example.com", Role.ADMIN);
		String adminToken = login("a@example.com");
		String tokenB = login("b@example.com");
		String refreshB = refreshTokenOf("b@example.com");
		MockHttpSession sessionB = webLogin("b@example.com");

		assertThat(apiStatus(tokenB, "/api/rbac-test/admin")).isEqualTo(200);
		mvc.perform(get("/admin-test").session(sessionB)).andExpect(status().isOk());

		// no sleep: even a token minted in the same second as the change must die
		mvc.perform(bearer(json(patch("/api/users/" + b.getId() + "/role"), "{\"role\":\"MEMBER\"}"), adminToken))
				.andExpect(status().isOk());

		assertThat(apiStatus(tokenB, "/api/rbac-test/admin")).isEqualTo(401);
		assertThat(apiStatus(tokenB, "/api/auth/me")).isEqualTo(401);
		MvcResult refreshed = refresh(refreshB);
		assertThat(refreshed.getResponse().getStatus()).isEqualTo(401);
		assertThat(field(refreshed, "$.errorCode")).isEqualTo("E010");
		assertSessionEnded(sessionB); // the demoted admin loses admin pages at once

		Thread.sleep(1100); // a token minted in the same second as the change is rejected by design
		String fresh = login("b@example.com");
		assertThat(apiStatus(fresh, "/api/rbac-test/admin")).isEqualTo(403);
		assertThat(apiStatus(fresh, "/api/auth/me")).isEqualTo(200);
		mvc.perform(get("/admin-test").session(webLogin("b@example.com"))).andExpect(status().isForbidden());
	}

	@Test
	void disablingCutsOffEverythingAndEnablingDoesNotRevive() throws Exception {
		createUser("a@example.com", Role.ADMIN);
		User m = createUser("m@example.com", Role.MEMBER);
		String adminToken = login("a@example.com");
		String tokenM = login("m@example.com");
		String refreshM = refreshTokenOf("m@example.com");
		MockHttpSession sessionM = webLogin("m@example.com");
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tokenM)).andExpect(status().isOk());

		mvc.perform(bearer(json(patch("/api/users/" + m.getId()), "{\"enabled\":false}"), adminToken))
				.andExpect(status().isOk());
		assertThat(apiStatus(tokenM, "/api/auth/me")).isEqualTo(401);
		assertThat(refresh(refreshM).getResponse().getStatus()).isEqualTo(401);
		assertThat(mvc.perform(get("/account/password").session(sessionM)).andReturn().getResponse().getStatus())
				.isEqualTo(302);
		MvcResult denied = loginResult("m@example.com");
		assertThat(denied.getResponse().getStatus()).isEqualTo(403);
		assertThat(field(denied, "$.errorCode")).isEqualTo("E007");

		mvc.perform(bearer(json(patch("/api/users/" + m.getId()), "{\"enabled\":true}"), adminToken))
				.andExpect(status().isOk());
		assertThat(apiStatus(tokenM, "/api/auth/me")).isEqualTo(401); // the old token stays dead
		assertThat(refresh(refreshM).getResponse().getStatus()).isEqualTo(401);
		Thread.sleep(1100);
		assertThat(apiStatus(login("m@example.com"), "/api/auth/me")).isEqualTo(200);
	}

	@Test
	void deletingCutsOffEverything() throws Exception {
		createUser("a@example.com", Role.ADMIN);
		User m = createUser("m@example.com", Role.MEMBER);
		String adminToken = login("a@example.com");
		String tokenM = login("m@example.com");
		String refreshM = refreshTokenOf("m@example.com");
		MockHttpSession sessionM = webLogin("m@example.com");

		mvc.perform(bearer(delete("/api/users/" + m.getId()), adminToken)).andExpect(status().isNoContent());

		assertThat(apiStatus(tokenM, "/api/auth/me")).isEqualTo(401);
		MvcResult refreshed = refresh(refreshM);
		assertThat(refreshed.getResponse().getStatus()).isEqualTo(401);
		assertThat(field(refreshed, "$.errorCode")).isEqualTo("E010");
		assertThat(mvc.perform(get("/account/password").session(sessionM)).andReturn().getResponse().getStatus())
				.isEqualTo(302);
		MvcResult login = loginResult("m@example.com");
		assertThat(login.getResponse().getStatus()).isEqualTo(401);
		assertThat(field(login, "$.errorCode")).isEqualTo("E006");
		// all refresh tokens of the user were revoked
		assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
				Integer.class, m.getId())).isZero();
	}

	@Test
	void refreshWithASoftDeletedUserIsTheGeneric401NotA500() throws Exception {
		User m = createUser("m@example.com", Role.MEMBER);
		String refreshM = refreshTokenOf("m@example.com");
		// soft delete behind the application's back: the refresh token stays live in the database
		jdbc.update("update users set is_deleted = true where id = ?", m.getId());
		assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where revoked_at is null", Integer.class))
				.isEqualTo(1);

		MvcResult r = refresh(refreshM);
		assertThat(r.getResponse().getStatus()).isEqualTo(401);
		assertThat(field(r, "$.errorCode")).isEqualTo("E010");
		MvcResult unknown = refresh("x".repeat(43));
		assertThat(field(r, "$.message")).isEqualTo(field(unknown, "$.message"));
		// logout with such a token is still the silent 204
		mvc.perform(json(post("/api/auth/logout"), "{\"refreshToken\":\"" + refreshM + "\"}"))
				.andExpect(status().isNoContent());
	}

	@Test
	void webSessionAuthoritiesAreReloadedFromTheDatabaseEvenWithoutAnAccessBump() throws Exception {
		createUser("a@example.com", Role.ADMIN);
		MockHttpSession session = webLogin("a@example.com");
		String token = login("a@example.com");
		mvc.perform(get("/admin-test").session(session)).andExpect(status().isOk());
		assertThat(apiStatus(token, "/api/rbac-test/admin")).isEqualTo(200);

		jdbc.update("update users set role = 'MEMBER' where email = 'a@example.com'"); // no access_changed_at bump
		mvc.perform(get("/admin-test").session(session)).andExpect(status().isForbidden());
		assertThat(apiStatus(token, "/api/rbac-test/admin")).isEqualTo(403); // authorities come from the database
	}

	@Test
	void passwordChangeStillKeepsTheChangingSessionLoggedIn() throws Exception {
		createUser("a@example.com", Role.ADMIN);
		MockHttpSession session = webLogin("a@example.com");
		mvc.perform(post("/account/password").session(session).with(csrf()).param("currentPassword", PASSWORD)
				.param("newPassword", "another long password 1").param("confirmPassword", "another long password 1"))
				.andExpect(status().is3xxRedirection());
		mvc.perform(get("/admin-test").session(session)).andExpect(status().isOk());
	}

	@Test
	void anAccessChangeOfOneUserDoesNotAffectOthers() throws Exception {
		createUser("a@example.com", Role.ADMIN);
		User m = createUser("m@example.com", Role.MEMBER);
		createUser("other@example.com", Role.MEMBER);
		String adminToken = login("a@example.com");
		String other = login("other@example.com");
		mvc.perform(bearer(json(patch("/api/users/" + m.getId() + "/role"), "{\"role\":\"MANAGER\"}"), adminToken))
				.andExpect(status().isOk());
		assertThat(apiStatus(other, "/api/auth/me")).isEqualTo(200);
		assertThat(apiStatus(adminToken, "/api/users")).isEqualTo(200);
		mvc.perform(bearer(get("/api/users/" + m.getId()), adminToken)).andExpect(jsonPath("$.data.role").value("MANAGER"));
	}

}
