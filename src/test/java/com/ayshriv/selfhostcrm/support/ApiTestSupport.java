package com.ayshriv.selfhostcrm.support;

import com.ayshriv.selfhostcrm.TestcontainersConfiguration;
import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Shared setup for integration tests: clean tables, seed users, log in, call the API with a bearer token. */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@SpringBootTest
@AutoConfigureMockMvc
public abstract class ApiTestSupport {

	protected static final String PASSWORD = "correct horse battery";

	@Autowired
	protected MockMvc mvc;

	@Autowired
	protected JdbcTemplate jdbc;

	@Autowired
	protected UserRepository users;

	@Autowired
	protected PasswordEncoder encoder;

	@BeforeEach
	protected void cleanDatabase() {
		jdbc.execute("delete from user_log");
		jdbc.execute("delete from invitations");
		jdbc.execute("delete from refresh_tokens");
		jdbc.execute("delete from team_members");
		jdbc.execute("delete from teams");
		jdbc.execute("delete from users");
	}

	protected User createUser(String email, Role role) {
		User u = new User();
		u.setEmail(email);
		u.setDisplayName(email.substring(0, email.indexOf('@')));
		u.setPasswordHash(encoder.encode(PASSWORD));
		u.setRole(role);
		return users.saveAndFlush(u);
	}

	protected MvcResult loginResult(String email) throws Exception {
		return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
	}

	protected String login(String email) throws Exception {
		return JsonPath.read(loginResult(email).getResponse().getContentAsString(), "$.data.accessToken");
	}

	protected String refreshTokenOf(String email) throws Exception {
		return JsonPath.read(loginResult(email).getResponse().getContentAsString(), "$.data.refreshToken");
	}

	protected static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder builder, String token) {
		return token == null ? builder : builder.header("Authorization", "Bearer " + token);
	}

	protected static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
		return builder.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	protected static String field(MvcResult result, String path) throws Exception {
		Object value = JsonPath.read(result.getResponse().getContentAsString(), path);
		return String.valueOf(value);
	}

}
