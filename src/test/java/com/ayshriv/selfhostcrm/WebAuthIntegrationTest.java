package com.ayshriv.selfhostcrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.repository.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.security.lockout.max-attempts=3")
class WebAuthIntegrationTest {

	private static final String EMAIL = "jane@example.com";

	private static final String PASSWORD = "correct horse battery";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder encoder;

	@BeforeEach
	void seed() {
		jdbc.execute("delete from user_log");
		jdbc.execute("delete from refresh_tokens");
		jdbc.execute("delete from users");
		User u = new User();
		u.setEmail(EMAIL);
		u.setDisplayName("Jane");
		u.setPasswordHash(encoder.encode(PASSWORD));
		u.setRole(Role.MEMBER);
		users.saveAndFlush(u);
	}

	private MvcResult login(String email, String password) throws Exception {
		return mvc.perform(post("/login").with(csrf()).param("username", email).param("password", password))
				.andReturn();
	}

	private MockHttpSession loggedIn() throws Exception {
		MvcResult r = login(EMAIL, PASSWORD);
		assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/");
		return (MockHttpSession) r.getRequest().getSession(false);
	}

	private long logCount(String action, String sub) {
		return jdbc.queryForObject("select count(*) from user_log where action = ? and sub_action = ?",
				Long.class, action, sub);
	}

	@Test
	void unauthenticatedRequestRedirectsToLogin() throws Exception {
		mvc.perform(get("/")).andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));
		mvc.perform(get("/account/password")).andExpect(status().is3xxRedirection());
	}

	@Test
	void loginPageIsPublicAndWiresCsrfIntoHtmx() throws Exception {
		mvc.perform(get("/login")).andExpect(status().isOk())
				.andExpect(content().string(containsString("hx-headers=\"{&quot;X-CSRF-TOKEN&quot;:&quot;")))
				.andExpect(content().string(containsString("name=\"_csrf\"")));
	}

	@Test
	void loginSuccessCreatesSessionAndAuditEntry() throws Exception {
		MockHttpSession session = loggedIn();
		assertThat(logCount("LOGIN", "SUCCESS")).isEqualTo(1);
		assertThat(jdbc.queryForObject("select last_login_at is not null from users", Boolean.class)).isTrue();
		mvc.perform(get("/").session(session)).andExpect(status().isOk())
				.andExpect(content().string(containsString("Welcome, ")));
	}

	@Test
	void wrongPasswordAndUnknownEmailGiveTheSameResponse() throws Exception {
		MvcResult wrong = login(EMAIL, "wrong password here");
		MvcResult unknown = login("nobody@example.com", "wrong password here");
		assertThat(wrong.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
		assertThat(unknown.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
		// unknown email: no audit row (no user), existing email: one FAILURE row
		assertThat(logCount("LOGIN", "FAILURE")).isEqualTo(1);
		assertThat(jdbc.queryForObject("select failed_login_count from users", Integer.class)).isEqualTo(1);
	}

	@Test
	void repeatedFailuresLockTheAccountEvenForTheRightPassword() throws Exception {
		for (int i = 0; i < 3; i++) {
			login(EMAIL, "wrong password here");
		}
		assertThat(jdbc.queryForObject("select locked_until is not null from users", Boolean.class)).isTrue();
		MvcResult r = login(EMAIL, PASSWORD);
		assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
		// lock expires: right password works again and counters reset
		jdbc.update("update users set locked_until = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(5)));
		assertThat(login(EMAIL, PASSWORD).getResponse().getRedirectedUrl()).isEqualTo("/");
		assertThat(jdbc.queryForObject("select failed_login_count from users", Integer.class)).isZero();
	}

	@Test
	void disabledUserCannotLogIn() throws Exception {
		jdbc.update("update users set enabled = false");
		assertThat(login(EMAIL, PASSWORD).getResponse().getRedirectedUrl()).isEqualTo("/login?disabled");
		assertThat(login(EMAIL, "wrong password here").getResponse().getRedirectedUrl()).isEqualTo("/login?error");
	}

	@Test
	void disablingAUserEndsTheirLiveSession() throws Exception {
		MockHttpSession session = loggedIn();
		jdbc.update("update users set enabled = false");
		mvc.perform(get("/").session(session)).andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));
	}

	@Test
	void postsRequireCsrf() throws Exception {
		mvc.perform(post("/login").param("username", EMAIL).param("password", PASSWORD))
				.andExpect(status().isForbidden());
		MockHttpSession session = loggedIn();
		mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
		mvc.perform(post("/account/password").session(session).param("currentPassword", PASSWORD)
				.param("newPassword", "another long password").param("confirmPassword", "another long password"))
				.andExpect(status().isForbidden());
	}

	@Test
	void logoutEndsSessionAndIsAudited() throws Exception {
		MockHttpSession session = loggedIn();
		mvc.perform(post("/logout").session(session).with(csrf())).andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login?logout"));
		assertThat(logCount("LOGOUT", "SUCCESS")).isEqualTo(1);
		mvc.perform(get("/").session(session)).andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));
	}

	@Test
	void changingThePasswordEndsOtherWebSessionsButKeepsTheCurrentOne() throws Exception {
		MockHttpSession current = loggedIn();
		MockHttpSession other = loggedIn();
		mvc.perform(get("/").session(other)).andExpect(status().isOk());

		mvc.perform(post("/account/password").session(current).with(csrf()).param("currentPassword", PASSWORD)
				.param("newPassword", "another long password").param("confirmPassword", "another long password"))
				.andExpect(status().is3xxRedirection());

		mvc.perform(get("/").session(current)).andExpect(status().isOk());
		mvc.perform(get("/").session(other)).andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));
	}

	@Test
	void aSessionCreatedBeforeThePasswordChangeButLoggedInAfterItIsNotEnded() throws Exception {
		// anonymous session exists first (as when the login page was opened earlier)
		MockHttpSession early = (MockHttpSession) mvc.perform(get("/login")).andReturn().getRequest()
				.getSession(true);
		jdbc.update("update users set credentials_changed_at = now()");
		Thread.sleep(20);
		MvcResult r = mvc.perform(post("/login").session(early).with(csrf()).param("username", EMAIL)
				.param("password", PASSWORD)).andReturn();
		assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/");
		mvc.perform(get("/").session((MockHttpSession) r.getRequest().getSession(false))).andExpect(status().isOk());
	}

	@Test
	void changePasswordRequiresCurrentPasswordAndRevokesRefreshTokens() throws Exception {
		MockHttpSession session = loggedIn();
		Long userId = jdbc.queryForObject("select id from users", Long.class);
		jdbc.update("""
				insert into refresh_tokens (created_on, updated_on, user_id, token_hash, expires_at, family_id)
				values (now(), now(), ?, ?, now() + interval '1 day', ?)""", userId, "f".repeat(64),
				UUID.randomUUID());

		mvc.perform(post("/account/password").session(session).with(csrf()).param("currentPassword", "not it")
				.param("newPassword", "another long password").param("confirmPassword", "another long password"))
				.andExpect(status().isOk()).andExpect(content().string(containsString("Current password is incorrect")));
		assertThat(logCount("PASSWORD_CHANGED", "FAILURE")).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where revoked_at is null", Long.class))
				.isEqualTo(1);

		mvc.perform(post("/account/password").session(session).with(csrf()).param("currentPassword", PASSWORD)
				.param("newPassword", "another long password").param("confirmPassword", "another long password"))
				.andExpect(status().is3xxRedirection());
		assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where revoked_at is null", Long.class))
				.isZero();
		assertThat(logCount("PASSWORD_CHANGED", "SUCCESS")).isEqualTo(1);
		assertThat(login(EMAIL, "another long password").getResponse().getRedirectedUrl()).isEqualTo("/");
	}

}
