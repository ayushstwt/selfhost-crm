package com.ayshriv.selfhostcrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.support.ApiTestSupport;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Every team action writes one audit entry (actor, target TEAM, affected user); refused actions write none. */
class TeamAuditIntegrationTest extends ApiTestSupport {

	private String audit(String token, String query) throws Exception {
		return mvc.perform(bearer(get("/api/audit" + query), token)).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
	}

	@Test
	void everyTeamActionIsAuditedWithTeamTargetAndAffectedUser() throws Exception {
		User admin = createUser("admin@example.com", Role.ADMIN);
		User member = createUser("member@example.com", Role.MEMBER);
		String token = login("admin@example.com");

		MvcResult created = mvc.perform(bearer(json(post("/api/teams"), "{\"name\":\"Sales\"}"), token))
				.andExpect(status().isCreated()).andReturn();
		long team = Long.parseLong(field(created, "$.data.id"));
		mvc.perform(bearer(json(patch("/api/teams/" + team), "{\"description\":\"Inside sales\"}"), token))
				.andExpect(status().isOk());
		String member1 = "/api/teams/" + team + "/members/" + member.getId();
		mvc.perform(bearer(json(put(member1), "{\"teamRole\":\"MEMBER\"}"), token)).andExpect(status().isCreated());
		mvc.perform(bearer(json(put(member1), "{\"teamRole\":\"LEAD\"}"), token)).andExpect(status().isOk());
		mvc.perform(bearer(json(put(member1), "{\"teamRole\":\"LEAD\"}"), token)).andExpect(status().isOk()); // no-op
		mvc.perform(bearer(delete(member1), token)).andExpect(status().isNoContent());
		mvc.perform(bearer(delete("/api/teams/" + team), token)).andExpect(status().isNoContent());

		for (String action : new String[] { "TEAM_CREATED", "TEAM_UPDATED", "TEAM_MEMBER_ADDED",
				"TEAM_MEMBER_ROLE_CHANGED", "TEAM_MEMBER_REMOVED", "TEAM_DELETED" }) {
			String body = audit(token, "?action=" + action);
			assertThat((Integer) JsonPath.read(body, "$.metadata.totalItems")).as(action).isEqualTo(1);
			assertThat(JsonPath.<String>read(body, "$.data[0].targetType")).isEqualTo("TEAM");
			assertThat(JsonPath.<Number>read(body, "$.data[0].targetId").longValue()).isEqualTo(team);
			assertThat(JsonPath.<Number>read(body, "$.data[0].actorId").longValue()).isEqualTo(admin.getId());
			if (action.startsWith("TEAM_MEMBER")) {
				assertThat(JsonPath.<Number>read(body, "$.data[0].relatedUserId").longValue())
						.isEqualTo(member.getId());
			}
			else {
				assertThat(JsonPath.<Object>read(body, "$.data[0].relatedUserId")).isNull();
			}
		}
		String teamEntries = audit(token, "?targetType=TEAM&targetId=" + team);
		assertThat((Integer) JsonPath.read(teamEntries, "$.metadata.totalItems")).isEqualTo(6);
		assertThat(audit(token, "?targetType=USER")).contains("\"totalItems\":0");
		assertThat(teamEntries).doesNotContain("Sales", "Inside sales", "password", "token");
	}

	@Test
	void refusedTeamActionsAreNotAudited() throws Exception {
		createUser("admin@example.com", Role.ADMIN);
		User manager = createUser("manager@example.com", Role.MANAGER);
		User member = createUser("member@example.com", Role.MEMBER);
		String adminToken = login("admin@example.com");
		long team = Long.parseLong(field(mvc.perform(bearer(json(post("/api/teams"), "{\"name\":\"Ops\"}"),
				adminToken)).andReturn(), "$.data.id"));
		String managerToken = login(manager.getEmail());
		mvc.perform(bearer(json(post("/api/teams"), "{\"name\":\"Nope\"}"), managerToken))
				.andExpect(status().isForbidden());
		mvc.perform(bearer(json(put("/api/teams/" + team + "/members/" + member.getId()),
				"{\"teamRole\":\"MEMBER\"}"), managerToken)).andExpect(status().isForbidden());
		mvc.perform(bearer(json(post("/api/teams"), "{\"name\":\"ops\"}"), adminToken)).andExpect(status().isConflict());
		assertThat((Integer) JsonPath.read(audit(adminToken, "?targetType=TEAM"), "$.metadata.totalItems"))
				.isEqualTo(1); // only the successful TEAM_CREATED
	}

}
