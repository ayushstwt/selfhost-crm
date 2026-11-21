package com.ayshriv.selfhostcrm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayshriv.selfhostcrm.TestcontainersConfiguration;
import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The admin pages under the prod profile and behind a reverse proxy (forwarded headers, htmx webjar, no leaks). */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("prod")
@SpringBootTest(properties = { "DB_PASSWORD=test-only-db-password-1234", "app.base-url=https://crm.example.test",
		"app.security.jwt.secret=test-only-jwt-secret-0123456789abcdef-test-only" })
@AutoConfigureMockMvc
class ProdProfileAdminWebTest {

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
		jdbc.execute("delete from invitations");
		jdbc.execute("delete from refresh_tokens");
		jdbc.execute("delete from team_members");
		jdbc.execute("delete from teams");
		jdbc.execute("delete from users");
		user("admin@example.com", Role.ADMIN);
		user("member@example.com", Role.MEMBER);
	}

	private void user(String email, Role role) {
		User u = new User();
		u.setEmail(email);
		u.setDisplayName(email);
		u.setPasswordHash(encoder.encode(PASSWORD));
		u.setRole(role);
		users.saveAndFlush(u);
	}

	private MockHttpSession session(String email) throws Exception {
		return (MockHttpSession) mvc.perform(post("/login").with(csrf()).param("username", email)
				.param("password", PASSWORD)).andReturn().getRequest().getSession(false);
	}

	@Test
	void htmxWebjarIsServedAndPagesRenderUnderProd() throws Exception {
		mvc.perform(get("/webjars/htmx.org/2.0.11/dist/htmx.min.js")).andExpect(status().isOk())
				.andExpect(content().string(containsString("htmx")));
		MockHttpSession admin = session("admin@example.com");
		for (String path : new String[] { "/admin/users", "/admin/teams", "/admin/audit" }) {
			mvc.perform(get(path).session(admin)).andExpect(status().isOk())
					.andExpect(content().string(containsString("/webjars/htmx.org/2.0.11/dist/htmx.min.js")))
					.andExpect(content().string(containsString("hx-headers=\"{&quot;X-CSRF-TOKEN&quot;")));
		}
	}

	@Test
	void redirectsAndPagesUseThePublicSchemeAndHostBehindAProxy() throws Exception {
		MockHttpSession admin = session("admin@example.com");
		mvc.perform(post("/admin/teams").session(admin).with(csrf()).param("name", "Proxy Team")
				.header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "crm.example.test"))
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string("Location", startsWith("https://crm.example.test/admin/teams/")));
		// HTMX redirect header is a path, so it is correct behind any proxy prefix-free setup
		var htmx = mvc.perform(post("/admin/teams").session(admin).with(csrf()).param("name", "Proxy Team 2")
				.header("HX-Request", "true").header("X-Forwarded-Proto", "https")
				.header("X-Forwarded-Host", "crm.example.test")).andReturn().getResponse();
		assertThat(htmx.getHeader("HX-Redirect")).startsWith("/admin/teams/");
		// unauthenticated users are sent to the public login URL
		mvc.perform(get("/admin/users").header("X-Forwarded-Proto", "https").header("X-Forwarded-Host",
				"crm.example.test")).andExpect(header().string("Location", "https://crm.example.test/login"));
	}

	@Test
	void forbiddenPageUnderProdShowsNoInternals() throws Exception {
		String html = mvc.perform(get("/admin/audit").session(session("member@example.com")))
				.andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
		assertThat(html).contains("403").doesNotContain("Exception", "at com.ayshriv", "Whitelabel", "trace");
	}

}
