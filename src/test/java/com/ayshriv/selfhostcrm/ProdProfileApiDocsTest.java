package com.ayshriv.selfhostcrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.repository.UserRepository;
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

/** Swagger UI and the OpenAPI document must not be reachable under the prod profile, signed in or not. */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("prod")
@SpringBootTest(properties = { "DB_PASSWORD=test-only-db-password-1234", "app.base-url=https://crm.example.test",
		"app.security.jwt.secret=test-only-jwt-secret-0123456789abcdef-test-only" })
@AutoConfigureMockMvc
class ProdProfileApiDocsTest {

	private static final String[] DOC_PATHS = { "/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs",
			"/v3/api-docs/swagger-config" };

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder encoder;

	@Test
	void docsAreNotExposedToAnonymousUsers() throws Exception {
		for (String path : DOC_PATHS) {
			int status = mvc.perform(get(path)).andReturn().getResponse().getStatus();
			assertThat(status).as(path).isNotEqualTo(200);
		}
	}

	@Test
	void docsAreNotExposedToSignedInUsersEither() throws Exception {
		jdbc.execute("delete from user_log");
		jdbc.execute("delete from invitations");
		jdbc.execute("delete from refresh_tokens");
		jdbc.execute("delete from users");
		User u = new User();
		u.setEmail("prod-docs@example.com");
		u.setDisplayName("Prod Docs");
		u.setPasswordHash(encoder.encode("correct horse battery"));
		u.setRole(Role.ADMIN);
		users.saveAndFlush(u);

		MockHttpSession session = (MockHttpSession) mvc.perform(post("/login").with(csrf())
				.param("username", "prod-docs@example.com").param("password", "correct horse battery"))
				.andReturn().getRequest().getSession(false);
		assertThat(session).isNotNull();

		for (String path : DOC_PATHS) {
			var response = mvc.perform(get(path).session(session)).andReturn().getResponse();
			assertThat(response.getStatus()).as(path).isEqualTo(404);
			assertThat(response.getContentAsString()).as(path).doesNotContain("openapi", "swagger-ui");
		}
	}

}
