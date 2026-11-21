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
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@SpringBootTest
@AutoConfigureMockMvc
class UserActivityIntegrationTest {

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
		create("me@example.com");
		create("other@example.com");
	}

	private void create(String email) {
		User u = new User();
		u.setEmail(email);
		u.setDisplayName(email.substring(0, email.indexOf('@')));
		u.setPasswordHash(encoder.encode(PASSWORD));
		u.setRole(Role.MEMBER);
		users.saveAndFlush(u);
	}

	private String login(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.data.accessToken");
	}

	@Test
	void requiresAuthentication() throws Exception {
		mvc.perform(get("/api/users/me/activity")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.errorCode").value("E003"));
	}

	@Test
	void returnsOnlyOwnEntriesNewestFirstWithPageMetadata() throws Exception {
		login("other@example.com");
		login("other@example.com");
		for (int i = 0; i < 3; i++) {
			login("me@example.com");
			Thread.sleep(5);
		}
		String token = login("me@example.com"); // 4 entries for "me"

		mvc.perform(get("/api/users/me/activity").param("size", "3")
				.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("success"))
				.andExpect(jsonPath("$.data.length()").value(3))
				.andExpect(jsonPath("$.data[0].action").value("LOGIN"))
				.andExpect(jsonPath("$.data[0].subAction").value("SUCCESS"))
				.andExpect(jsonPath("$.data[0].userName").value("me"))
				.andExpect(jsonPath("$.metadata.currentPage").value(0))
				.andExpect(jsonPath("$.metadata.pageSize").value(3))
				.andExpect(jsonPath("$.metadata.totalPages").value(2))
				.andExpect(jsonPath("$.metadata.totalItems").value(4));

		String second = mvc.perform(get("/api/users/me/activity").param("page", "1").param("size", "3")
				.header("Authorization", "Bearer " + token)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1)).andReturn().getResponse().getContentAsString();
		assertThat(second).doesNotContain("other");

		// newest first: createdOn is non-increasing
		String all = mvc.perform(get("/api/users/me/activity").header("Authorization", "Bearer " + token))
				.andReturn().getResponse().getContentAsString();
		java.util.List<String> times = JsonPath.read(all, "$.data[*].createdOn");
		assertThat(times).isSortedAccordingTo(java.util.Comparator.reverseOrder());
	}

	@Test
	void sizeIsCappedAndValidated() throws Exception {
		String token = login("me@example.com");
		for (String[] bad : new String[][] { { "size", "101" }, { "size", "0" }, { "page", "-1" } }) {
			mvc.perform(get("/api/users/me/activity").param(bad[0], bad[1])
					.header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errorCode").value("E005"))
					.andExpect(jsonPath("$.errors[0].message").exists());
		}
		mvc.perform(get("/api/users/me/activity").param("size", "100").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());
	}

	@Test
	void responseHasNoInternalFields() throws Exception {
		String token = login("me@example.com");
		String body = mvc.perform(get("/api/users/me/activity").header("Authorization", "Bearer " + token))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("passwordHash", "tokenHash", "\"active\"", "\"deleted\"", "\"user\"");
	}

}
