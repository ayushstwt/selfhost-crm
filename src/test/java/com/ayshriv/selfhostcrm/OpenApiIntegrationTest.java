package com.ayshriv.selfhostcrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder encoder;

	@Test
	void apiDocsAreNotPublicButContainTheEnvelopeExamplesForASignedInUser() throws Exception {
		jdbc.execute("delete from user_log");
		jdbc.execute("delete from invitations");
		jdbc.execute("delete from refresh_tokens");
		jdbc.execute("delete from users");
		User u = new User();
		u.setEmail("docs@example.com");
		u.setDisplayName("Docs");
		u.setPasswordHash(encoder.encode("correct horse battery"));
		u.setRole(Role.MEMBER);
		users.saveAndFlush(u);

		mvc.perform(get("/v3/api-docs")).andExpect(status().isFound());

		MockHttpSession session = (MockHttpSession) mvc.perform(post("/login").with(csrf())
				.param("username", "docs@example.com").param("password", "correct horse battery"))
				.andReturn().getRequest().getSession(false);
		String docs = mvc.perform(get("/v3/api-docs").session(session)).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();

		assertThat(docs).contains("/api/auth/login", "/api/auth/refresh", "/api/auth/logout", "/api/auth/me",
				"/api/auth/change-password", "/api/users/me/activity");
		assertThat(docs).contains("bearerAuth", "LoginSuccess", "ValidationError", "AuthenticationRequired",
				"ActivityPage", "AccessDenied", "\"currentPage\"");
	}

}
