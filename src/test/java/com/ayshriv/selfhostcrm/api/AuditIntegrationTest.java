package com.ayshriv.selfhostcrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.domain.UserAction;
import com.ayshriv.selfhostcrm.support.ApiTestSupport;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class AuditIntegrationTest extends ApiTestSupport {

	private String body(String token, String query) throws Exception {
		return mvc.perform(bearer(get("/api/audit" + query), token)).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
	}

	private int count(String token, String query) throws Exception {
		return ((Number) JsonPath.read(body(token, query), "$.metadata.totalItems")).intValue();
	}

	@Test
	void onlyAdminCanReadTheAuditLog() throws Exception {
		createUser("admin@example.com", Role.ADMIN);
		createUser("manager@example.com", Role.MANAGER);
		createUser("member@example.com", Role.MEMBER);
		String[] actors = { null, login("member@example.com"), login("manager@example.com"),
				login("admin@example.com") };
		int[] expected = { 401, 403, 403, 200 };
		for (int i = 0; i < actors.length; i++) {
			assertThat(mvc.perform(bearer(get("/api/audit"), actors[i])).andReturn().getResponse().getStatus())
					.isEqualTo(expected[i]);
		}
	}

	@Test
	void everyAdministrativeActionWritesAnAuditEntryWithActorAndTarget() throws Exception {
		User admin = createUser("admin@example.com", Role.ADMIN);
		User member = createUser("member@example.com", Role.MEMBER);
		String token = login("admin@example.com");

		// invitations: create, replace (revokes the first), revoke, accept
		MvcResult first = mvc.perform(bearer(json(post("/api/invitations"),
				"{\"email\":\"new@example.com\",\"role\":\"MEMBER\"}"), token)).andReturn();
		long firstId = ((Number) JsonPath.read(first.getResponse().getContentAsString(), "$.data.id")).longValue();
		MvcResult second = mvc.perform(bearer(json(post("/api/invitations"),
				"{\"email\":\"new@example.com\",\"role\":\"MEMBER\"}"), token)).andReturn();
		long secondId = ((Number) JsonPath.read(second.getResponse().getContentAsString(), "$.data.id")).longValue();
		String url = JsonPath.read(second.getResponse().getContentAsString(), "$.data.acceptUrl");
		MvcResult accepted = mvc.perform(json(post("/api/invitations/accept"), "{\"token\":\"%s\",\"displayName\":\"N\",\"password\":\"%s\"}"
				.formatted(url.substring(url.lastIndexOf('/') + 1), PASSWORD))).andReturn();
		long newUserId = ((Number) JsonPath.read(accepted.getResponse().getContentAsString(), "$.data.id")).longValue();
		MvcResult third = mvc.perform(bearer(json(post("/api/invitations"),
				"{\"email\":\"third@example.com\",\"role\":\"MEMBER\"}"), token)).andReturn();
		long thirdId = ((Number) JsonPath.read(third.getResponse().getContentAsString(), "$.data.id")).longValue();
		mvc.perform(bearer(delete("/api/invitations/" + thirdId), token)).andExpect(status().isNoContent());

		// users: rename, disable, enable, change role, delete
		long m = member.getId();
		mvc.perform(bearer(json(patch("/api/users/" + m), "{\"displayName\":\"Renamed\"}"), token)).andExpect(status().isOk());
		mvc.perform(bearer(json(patch("/api/users/" + m), "{\"enabled\":false}"), token)).andExpect(status().isOk());
		mvc.perform(bearer(json(patch("/api/users/" + m), "{\"enabled\":true}"), token)).andExpect(status().isOk());
		mvc.perform(bearer(json(patch("/api/users/" + m + "/role"), "{\"role\":\"MANAGER\"}"), token)).andExpect(status().isOk());
		mvc.perform(bearer(delete("/api/users/" + m), token)).andExpect(status().isNoContent());

		String all = body(token, "?size=100");
		List<String> actions = JsonPath.read(all, "$.data[*].action");
		for (UserAction expected : List.of(UserAction.INVITATION_CREATED, UserAction.INVITATION_REVOKED,
				UserAction.INVITATION_ACCEPTED, UserAction.USER_UPDATED, UserAction.USER_DISABLED,
				UserAction.USER_ENABLED, UserAction.USER_ROLE_CHANGED, UserAction.USER_DELETED)) {
			assertThat(actions).as(expected.name()).contains(expected.name());
		}
		assertThat(count(token, "?action=INVITATION_CREATED")).isEqualTo(3);
		assertThat(count(token, "?action=INVITATION_REVOKED")).isEqualTo(2); // replaced + revoked

		// actor and target
		String revokedFirst = body(token, "?action=INVITATION_REVOKED&targetId=" + firstId);
		assertThat((Integer) JsonPath.read(revokedFirst, "$.metadata.totalItems")).isEqualTo(1);
		assertThat(JsonPath.<Number>read(revokedFirst, "$.data[0].actorId").longValue()).isEqualTo(admin.getId());
		assertThat(JsonPath.<String>read(revokedFirst, "$.data[0].targetType")).isEqualTo("INVITATION");
		String acceptedEntry = body(token, "?action=INVITATION_ACCEPTED");
		assertThat(JsonPath.<Number>read(acceptedEntry, "$.data[0].actorId").longValue()).isEqualTo(newUserId); // the new user
		assertThat(JsonPath.<Number>read(acceptedEntry, "$.data[0].targetId").longValue()).isEqualTo(secondId);
		String deleted = body(token, "?action=USER_DELETED");
		assertThat(JsonPath.<String>read(deleted, "$.data[0].targetType")).isEqualTo("USER");
		assertThat(JsonPath.<Number>read(deleted, "$.data[0].targetId").longValue()).isEqualTo(m);
		assertThat(JsonPath.<String>read(deleted, "$.data[0].actorName")).isEqualTo("admin");
	}

	@Test
	void refusedActionsAreNotAudited() throws Exception {
		User admin = createUser("admin@example.com", Role.ADMIN);
		createUser("manager@example.com", Role.MANAGER);
		String manager = login("manager@example.com");
		String adminToken = login("admin@example.com");
		mvc.perform(bearer(json(patch("/api/users/" + admin.getId()), "{\"displayName\":\"x\"}"), manager))
				.andExpect(status().isForbidden());
		mvc.perform(bearer(json(patch("/api/users/" + admin.getId() + "/role"), "{\"role\":\"MEMBER\"}"), adminToken))
				.andExpect(status().isForbidden()); // self
		assertThat(count(adminToken, "?targetType=USER")).isZero();
	}

	@Test
	void filtersPaginationAndOrdering() throws Exception {
		User admin = createUser("admin@example.com", Role.ADMIN);
		createUser("member@example.com", Role.MEMBER);
		String token = login("admin@example.com");
		for (int i = 0; i < 5; i++) {
			mvc.perform(bearer(json(post("/api/invitations"),
					"{\"email\":\"p" + i + "@example.com\",\"role\":\"MEMBER\"}"), token)).andExpect(status().isCreated());
			Thread.sleep(3);
		}
		// logins are audited too: 1 login by admin + 5 INVITATION_CREATED + 1 login by member
		login("member@example.com");

		assertThat(count(token, "")).isEqualTo(7);
		assertThat(count(token, "?actorId=" + admin.getId())).isEqualTo(6);
		assertThat(count(token, "?actorId=" + admin.getId() + "&action=LOGIN")).isEqualTo(1);
		assertThat(count(token, "?action=INVITATION_CREATED&targetType=INVITATION")).isEqualTo(5);
		assertThat(count(token, "?targetType=USER")).isZero();

		String page0 = body(token, "?action=INVITATION_CREATED&size=2&page=0");
		String page2 = body(token, "?action=INVITATION_CREATED&size=2&page=2");
		assertThat((Integer) JsonPath.read(page0, "$.metadata.totalPages")).isEqualTo(3);
		assertThat((Integer) JsonPath.read(page0, "$.metadata.pageSize")).isEqualTo(2);
		assertThat((Integer) JsonPath.read(page0, "$.data.length()")).isEqualTo(2);
		assertThat((Integer) JsonPath.read(page2, "$.data.length()")).isEqualTo(1);
		List<String> times = JsonPath.read(body(token, "?size=100"), "$.data[*].createdOn");
		assertThat(times).isSortedAccordingTo(java.util.Comparator.reverseOrder());

		// time window
		assertThat(count(token, "?from=2999-01-01T00:00:00Z")).isZero();
		assertThat(count(token, "?to=2000-01-01T00:00:00Z")).isZero();
		assertThat(count(token, "?from=2000-01-01T00:00:00Z&to=2999-01-01T00:00:00Z")).isEqualTo(7);
		mvc.perform(bearer(get("/api/audit").param("from", "2999-01-01T00:00:00Z").param("to", "2000-01-01T00:00:00Z"),
				token)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("E005"));

		// bad parameters
		mvc.perform(bearer(get("/api/audit").param("size", "101"), token)).andExpect(status().isBadRequest());
		mvc.perform(bearer(get("/api/audit").param("page", "-1"), token)).andExpect(status().isBadRequest());
		mvc.perform(bearer(get("/api/audit").param("action", "NOPE"), token)).andExpect(status().isBadRequest());
		mvc.perform(bearer(get("/api/audit").param("from", "yesterday"), token)).andExpect(status().isBadRequest());
		mvc.perform(bearer(get("/api/audit").param("size", "100"), token)).andExpect(status().isOk());
	}

	@Test
	void entriesOfADeletedActorStayVisibleAndNothingSensitiveIsExposed() throws Exception {
		createUser("admin@example.com", Role.ADMIN);
		User manager = createUser("manager@example.com", Role.MANAGER);
		String adminToken = login("admin@example.com");
		String managerToken = login("manager@example.com");
		MvcResult created = mvc.perform(bearer(json(post("/api/invitations"),
				"{\"email\":\"x@example.com\",\"role\":\"MEMBER\"}"), managerToken)).andReturn();
		String rawUrl = JsonPath.read(created.getResponse().getContentAsString(), "$.data.acceptUrl");
		mvc.perform(bearer(delete("/api/users/" + manager.getId()), adminToken)).andExpect(status().isNoContent());

		String log = body(adminToken, "?actorId=" + manager.getId() + "&size=100");
		Set<String> actions = ((List<String>) JsonPath.read(log, "$.data[*].action")).stream()
				.collect(Collectors.toSet());
		assertThat(actions).contains("INVITATION_CREATED", "LOGIN");
		assertThat((List<Object>) JsonPath.read(log, "$.data[*].actorName")).containsOnlyNulls();

		String everything = body(adminToken, "?size=100");
		String token = rawUrl.substring(rawUrl.lastIndexOf('/') + 1);
		assertThat(everything).doesNotContain(token, PASSWORD, "passwordHash", "tokenHash", "$2a$", "\"active\"",
				"\"deleted\"", "accessToken", "refreshToken");
	}

}
