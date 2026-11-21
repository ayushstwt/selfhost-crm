package com.ayshriv.selfhostcrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.support.ApiTestSupport;
import jakarta.persistence.EntityManagerFactory;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Team API: role matrix, team rules, uniqueness, soft delete, access cut-off and query counts. */
class TeamApiIntegrationTest extends ApiTestSupport {

	@Autowired
	EntityManagerFactory emf;

	User admin;

	User mgrLead; // MANAGER, LEAD of team A

	User mgrOther; // MANAGER, not in team A

	User member; // MEMBER, plain member of team A

	User m2; // MEMBER, plain member of team A

	User outsider; // MEMBER, in no team

	long teamA;

	long teamB;

	@BeforeEach
	void seed() {
		admin = createUser("admin@example.com", Role.ADMIN);
		mgrLead = createUser("lead@example.com", Role.MANAGER);
		mgrOther = createUser("other@example.com", Role.MANAGER);
		member = createUser("member@example.com", Role.MEMBER);
		m2 = createUser("m2@example.com", Role.MEMBER);
		outsider = createUser("outsider@example.com", Role.MEMBER);
		teamA = insertTeam("Team A");
		teamB = insertTeam("Team B");
		resetMemberships();
	}

	private long insertTeam(String name) {
		return jdbc.queryForObject(
				"insert into teams (created_on, updated_on, name) values (now(), now(), ?) returning id", Long.class,
				name);
	}

	private void addMember(long team, User user, String teamRole) {
		jdbc.update("insert into team_members (created_on, updated_on, team_id, user_id, team_role) "
				+ "values (now(), now(), ?, ?, ?)", team, user.getId(), teamRole);
	}

	private void resetMemberships() {
		jdbc.update("delete from team_members");
		jdbc.update("update teams set is_deleted = false");
		addMember(teamA, mgrLead, "LEAD");
		addMember(teamA, member, "MEMBER");
		addMember(teamA, m2, "MEMBER");
	}

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	private MockHttpServletRequestBuilder req(String method, String path, String body, String token) {
		MockHttpServletRequestBuilder builder = switch (method) {
			case "GET" -> get(path);
			case "POST" -> post(path);
			case "PUT" -> put(path);
			case "PATCH" -> patch(path);
			default -> delete(path);
		};
		if (body != null) {
			builder = json(builder, body);
		}
		return bearer(builder, token);
	}

	private int call(String method, String path, String body, String email) throws Exception {
		String token = email == null ? null : login(email);
		return mvc.perform(req(method, path, body, token)).andReturn().getResponse().getStatus();
	}

	private record Endpoint(String name, String method, String path, String body, int anonymous, int member,
			int managerLead, int managerOther, int admin) {

		@Override
		public String toString() {
			return name;
		}

	}

	static Stream<Arguments> matrix() {
		return Stream.of(
				new Endpoint("list teams", "GET", "/api/teams", null, 401, 200, 200, 200, 200),
				new Endpoint("get team A", "GET", "/api/teams/{A}", null, 401, 200, 200, 403, 200),
				new Endpoint("create team", "POST", "/api/teams", "{\"name\":\"Brand New\"}", 401, 403, 403, 403, 201),
				new Endpoint("rename team", "PATCH", "/api/teams/{A}", "{\"name\":\"Renamed\"}", 401, 403, 403, 403, 200),
				new Endpoint("delete team", "DELETE", "/api/teams/{A}", null, 401, 403, 403, 403, 204),
				new Endpoint("list members", "GET", "/api/teams/{A}/members", null, 401, 200, 200, 403, 200),
				new Endpoint("get member", "GET", "/api/teams/{A}/members/{M}", null, 401, 200, 200, 403, 200),
				new Endpoint("add member", "PUT", "/api/teams/{A}/members/{O}", "{\"teamRole\":\"MEMBER\"}", 401, 403, 201,
						403, 201),
				new Endpoint("change team role", "PUT", "/api/teams/{A}/members/{M}", "{\"teamRole\":\"LEAD\"}", 401, 403,
						200, 403, 200),
				new Endpoint("remove member", "DELETE", "/api/teams/{A}/members/{M}", null, 401, 403, 204, 403, 204),
				new Endpoint("teams of user (self = member)", "GET", "/api/users/{M}/teams", null, 401, 200, 403, 403, 200),
				new Endpoint("other team B", "GET", "/api/teams/{B}", null, 401, 403, 403, 403, 200),
				new Endpoint("unknown team", "GET", "/api/teams/999999", null, 401, 403, 403, 403, 404),
				new Endpoint("unknown team members", "GET", "/api/teams/999999/members", null, 401, 403, 403, 403, 404))
				.map(Arguments::of);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("matrix")
	void roleMatrix(Endpoint e) throws Exception {
		String[] actors = { null, member.getEmail(), mgrLead.getEmail(), mgrOther.getEmail(), admin.getEmail() };
		int[] expected = { e.anonymous(), e.member(), e.managerLead(), e.managerOther(), e.admin() };
		String path = e.path().replace("{A}", String.valueOf(teamA)).replace("{B}", String.valueOf(teamB))
				.replace("{M}", String.valueOf(member.getId())).replace("{O}", String.valueOf(outsider.getId()));
		for (int i = 0; i < actors.length; i++) {
			jdbc.update("delete from teams where name in ('Brand New', 'Renamed')");
			jdbc.update("update teams set name = 'Team A' where id = ?", teamA);
			resetMemberships();
			assertThat(call(e.method(), path, e.body(), actors[i])).as("%s as %s", e.name(),
					actors[i] == null ? "anonymous" : actors[i]).isEqualTo(expected[i]);
		}
	}

	@Test
	void memberSeesOnlyOwnTeamsAndManagerSeesOnlyTeamsTheyBelongTo() throws Exception {
		mvc.perform(bearer(get("/api/teams"), login(member.getEmail()))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].name").value("Team A"))
				.andExpect(jsonPath("$.data[0].memberCount").value(3))
				.andExpect(jsonPath("$.metadata.totalItems").value(1));
		mvc.perform(bearer(get("/api/teams"), login(mgrOther.getEmail()))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(0));
		mvc.perform(bearer(get("/api/teams"), login(admin.getEmail()))).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2));
		mvc.perform(bearer(get("/api/teams").param("q", "team b"), login(admin.getEmail())))
				.andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].name").value("Team B"));
		mvc.perform(bearer(get("/api/teams").param("q", "%"), login(admin.getEmail())))
				.andExpect(jsonPath("$.data.length()").value(0));
		mvc.perform(bearer(get("/api/teams").param("size", "101"), login(admin.getEmail())))
				.andExpect(status().isBadRequest());
	}

	@Test
	void nonAdminGetsTheSame403ForAnotherTeamWhetherOrNotItExists() throws Exception {
		String token = login(member.getEmail());
		mvc.perform(bearer(get("/api/teams/" + teamB), token)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errorCode").value("E018"));
		mvc.perform(bearer(get("/api/teams/999999"), token)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errorCode").value("E018"));
		mvc.perform(bearer(get("/api/teams/" + teamB + "/members"), token)).andExpect(status().isForbidden());
		mvc.perform(bearer(get("/api/teams/999999"), login(admin.getEmail()))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("E019"));
	}

	@Test
	void managerOfTeamACannotTouchTeamB() throws Exception {
		String token = login(mgrLead.getEmail());
		String body = "{\"teamRole\":\"MEMBER\"}";
		mvc.perform(bearer(json(put("/api/teams/" + teamB + "/members/" + outsider.getId()), body), token))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("E018"));
		mvc.perform(bearer(delete("/api/teams/" + teamB + "/members/" + outsider.getId()), token))
				.andExpect(status().isForbidden());
		assertThat(count("select count(*) from team_members where team_id = ?", teamB)).isZero();
	}

	@Test
	void aManagerWhoIsOnlyAPlainMemberCannotChangeMembers() throws Exception {
		addMember(teamB, mgrOther, "MEMBER");
		String token = login(mgrOther.getEmail());
		mvc.perform(bearer(json(put("/api/teams/" + teamB + "/members/" + outsider.getId()),
				"{\"teamRole\":\"MEMBER\"}"), token)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errorCode").value("E018"));
		mvc.perform(bearer(get("/api/teams/" + teamB + "/members"), token)).andExpect(status().isOk());
	}

	@Test
	void plainTeamMemberCannotChangeMembers() throws Exception {
		String token = login(member.getEmail());
		mvc.perform(bearer(json(put("/api/teams/" + teamA + "/members/" + outsider.getId()),
				"{\"teamRole\":\"MEMBER\"}"), token)).andExpect(status().isForbidden());
		mvc.perform(bearer(delete("/api/teams/" + teamA + "/members/" + m2.getId()), token))
				.andExpect(status().isForbidden());
		assertThat(count("select count(*) from team_members where team_id = ?", teamA)).isEqualTo(3);
	}

	@Test
	void managerCannotAddOrRemoveAdminOrManagerAccountsOrThemselves() throws Exception {
		String token = login(mgrLead.getEmail());
		String body = "{\"teamRole\":\"MEMBER\"}";
		for (User forbidden : new User[] { admin, mgrOther, mgrLead }) {
			mvc.perform(bearer(json(put("/api/teams/" + teamA + "/members/" + forbidden.getId()), body), token))
					.andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("E013"));
		}
		// even when the row exists (put there directly), the manager cannot remove or promote it
		addMember(teamA, mgrOther, "MEMBER");
		mvc.perform(bearer(delete("/api/teams/" + teamA + "/members/" + mgrOther.getId()), token))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("E013"));
		mvc.perform(bearer(delete("/api/teams/" + teamA + "/members/" + mgrLead.getId()), token))
				.andExpect(status().isForbidden());
		assertThat(count("select count(*) from team_members where team_id = ? and user_id = ?", teamA,
				mgrOther.getId())).isEqualTo(1);
	}

	@Test
	void adminCanAddManagersAndAdminsAndALeadManagerCanPromoteAndDemoteMembers() throws Exception {
		String adminToken = login(admin.getEmail());
		mvc.perform(bearer(json(put("/api/teams/" + teamA + "/members/" + mgrOther.getId()),
				"{\"teamRole\":\"LEAD\"}"), adminToken)).andExpect(status().isCreated());
		String leadToken = login(mgrLead.getEmail());
		mvc.perform(bearer(json(put("/api/teams/" + teamA + "/members/" + member.getId()),
				"{\"teamRole\":\"LEAD\"}"), leadToken)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.teamRole").value("LEAD"));
		mvc.perform(bearer(json(put("/api/teams/" + teamA + "/members/" + member.getId()),
				"{\"teamRole\":\"MEMBER\"}"), leadToken)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.teamRole").value("MEMBER"));
	}

	@Test
	void putIsIdempotentCreatesWithLocationAndNeverDuplicates() throws Exception {
		String token = login(admin.getEmail());
		String url = "/api/teams/" + teamA + "/members/" + outsider.getId();
		MvcResult created = mvc.perform(bearer(json(put(url), "{\"teamRole\":\"MEMBER\"}"), token))
				.andExpect(status().isCreated()).andExpect(header().string("Location", org.hamcrest.Matchers.endsWith(url)))
				.andReturn();
		assertThat(created.getResponse().getHeader("Location")).isNotNull();
		mvc.perform(bearer(get(url), token)).andExpect(status().isOk());
		mvc.perform(bearer(json(put(url), "{\"teamRole\":\"MEMBER\"}"), token)).andExpect(status().isOk());
		mvc.perform(bearer(json(put(url), "{\"teamRole\":\"LEAD\"}"), token)).andExpect(status().isOk());
		assertThat(count("select count(*) from team_members where team_id = ? and user_id = ?", teamA,
				outsider.getId())).isEqualTo(1);
		mvc.perform(bearer(json(put(url), "{\"teamRole\":\"BOSS\"}"), token)).andExpect(status().isBadRequest());
		mvc.perform(bearer(json(put(url), "{}"), token)).andExpect(status().isBadRequest());
		mvc.perform(bearer(delete(url), token)).andExpect(status().isNoContent());
		mvc.perform(bearer(delete(url), token)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("E021"));
	}

	@Test
	void teamNamesAreUniqueIgnoringCaseAndReusableAfterDelete() throws Exception {
		String token = login(admin.getEmail());
		mvc.perform(bearer(json(post("/api/teams"), "{\"name\":\"  team a  \"}"), token))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("E020"));
		MvcResult created = mvc.perform(bearer(json(post("/api/teams"),
				"{\"name\":\" Support \",\"description\":\"Help desk\"}"), token)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.name").value("Support"))
				.andExpect(jsonPath("$.data.memberCount").value(0)).andReturn();
		String location = created.getResponse().getHeader("Location");
		assertThat(location).endsWith("/api/teams/" + field(created, "$.data.id"));
		mvc.perform(bearer(get(location), token)).andExpect(status().isOk());
		// rename onto an existing name conflicts; renaming to its own name (other case) is fine
		mvc.perform(bearer(json(patch("/api/teams/" + field(created, "$.data.id")), "{\"name\":\"TEAM B\"}"), token))
				.andExpect(status().isConflict());
		mvc.perform(bearer(json(patch("/api/teams/" + field(created, "$.data.id")), "{\"name\":\"SUPPORT\"}"), token))
				.andExpect(status().isOk());
		mvc.perform(bearer(json(patch("/api/teams/" + teamA), "{}"), token)).andExpect(status().isBadRequest());
		mvc.perform(bearer(delete("/api/teams/" + field(created, "$.data.id")), token)).andExpect(status().isNoContent());
		mvc.perform(bearer(json(post("/api/teams"), "{\"name\":\"support\"}"), token)).andExpect(status().isCreated());
		mvc.perform(bearer(json(post("/api/teams"), "{\"name\":\"   \"}"), token)).andExpect(status().isBadRequest());
	}

	@Test
	void aUserCanBelongToSeveralTeams() throws Exception {
		addMember(teamB, member, "LEAD");
		String token = login(member.getEmail());
		mvc.perform(bearer(get("/api/teams"), token)).andExpect(jsonPath("$.data.length()").value(2));
		mvc.perform(bearer(get("/api/users/" + member.getId() + "/teams"), token)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.metadata.totalItems").value(2));
		mvc.perform(bearer(get("/api/teams/" + teamB + "/members"), token)).andExpect(status().isOk());
		mvc.perform(bearer(get("/api/users/" + member.getId() + "/teams"), login(mgrOther.getEmail())))
				.andExpect(status().isForbidden());
		mvc.perform(bearer(get("/api/users/999999/teams"), login(admin.getEmail()))).andExpect(status().isNotFound());
		mvc.perform(bearer(get("/api/users/999999/teams"), token)).andExpect(status().isForbidden());
	}

	@Test
	void deletingATeamSoftDeletesItsMembershipsAndFreesTheName() throws Exception {
		String token = login(admin.getEmail());
		mvc.perform(bearer(delete("/api/teams/" + teamA), token)).andExpect(status().isNoContent());
		assertThat(count("select count(*) from team_members where team_id = ? and is_deleted = true", teamA))
				.isEqualTo(3);
		assertThat(count("select count(*) from teams where id = ? and is_deleted = true", teamA)).isEqualTo(1);
		mvc.perform(bearer(get("/api/teams/" + teamA), token)).andExpect(status().isNotFound());
		mvc.perform(bearer(get("/api/teams/" + teamA + "/members"), token)).andExpect(status().isNotFound());
		mvc.perform(bearer(delete("/api/teams/" + teamA), token)).andExpect(status().isNotFound());
		mvc.perform(bearer(get("/api/users/" + member.getId() + "/teams"), token))
				.andExpect(jsonPath("$.data.length()").value(0));
		mvc.perform(bearer(json(post("/api/teams"), "{\"name\":\"TEAM A\"}"), token)).andExpect(status().isCreated());
	}

	@Test
	void disabledAndDeletedUsersDisappearFromMemberListsAndCannotBeAdded() throws Exception {
		String token = login(admin.getEmail());
		mvc.perform(bearer(get("/api/teams/" + teamA + "/members"), token))
				.andExpect(jsonPath("$.metadata.totalItems").value(3));
		jdbc.update("update users set enabled = false where id = ?", m2.getId());
		mvc.perform(bearer(get("/api/teams/" + teamA + "/members"), token))
				.andExpect(jsonPath("$.metadata.totalItems").value(2));
		mvc.perform(bearer(get("/api/teams/" + teamA), token)).andExpect(jsonPath("$.data.memberCount").value(2));
		mvc.perform(bearer(json(put("/api/teams/" + teamA + "/members/" + m2.getId()), "{\"teamRole\":\"MEMBER\"}"),
				token)).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("E022"));
		jdbc.update("update users set enabled = true where id = ?", m2.getId());
		jdbc.update("update users set is_deleted = true where id = ?", member.getId());
		mvc.perform(bearer(get("/api/teams/" + teamA + "/members"), token))
				.andExpect(jsonPath("$.metadata.totalItems").value(2));
		mvc.perform(bearer(json(put("/api/teams/" + teamA + "/members/" + member.getId()), "{\"teamRole\":\"MEMBER\"}"),
				token)).andExpect(status().isNotFound());
		mvc.perform(bearer(get("/api/teams/" + teamB), token)).andExpect(status().isOk());
	}

	@Test
	void membersSeeNoEmailsButManagersAndAdminsDo() throws Exception {
		mvc.perform(bearer(get("/api/teams/" + teamA + "/members"), login(member.getEmail())))
				.andExpect(jsonPath("$.data[0].email").doesNotExist())
				.andExpect(jsonPath("$.data[0].displayName").exists());
		mvc.perform(bearer(get("/api/teams/" + teamA + "/members"), login(mgrLead.getEmail())))
				.andExpect(jsonPath("$.data[0].email").exists());
	}

	@Test
	void membershipChangesApplyToTheNextRequestWithoutCuttingTheSession() throws Exception {
		String token = login(outsider.getEmail());
		mvc.perform(bearer(get("/api/teams/" + teamA), token)).andExpect(status().isForbidden());
		mvc.perform(bearer(json(put("/api/teams/" + teamA + "/members/" + outsider.getId()),
				"{\"teamRole\":\"MEMBER\"}"), login(admin.getEmail()))).andExpect(status().isCreated());
		mvc.perform(bearer(get("/api/teams/" + teamA), token)).andExpect(status().isOk()); // same token, no re-login
		mvc.perform(bearer(delete("/api/teams/" + teamA + "/members/" + outsider.getId()), login(admin.getEmail())))
				.andExpect(status().isNoContent());
		mvc.perform(bearer(get("/api/teams/" + teamA), token)).andExpect(status().isForbidden()); // still signed in
	}

	@Test
	void losingLeadRightsCutsOffTheOldTokenAndGrantsDoNot() throws Exception {
		String adminToken = login(admin.getEmail());
		// removed as LEAD: old token dies
		String leadToken = login(mgrLead.getEmail());
		Thread.sleep(1100); // access_changed_at has whole-second precision
		leadToken = login(mgrLead.getEmail());
		mvc.perform(bearer(get("/api/teams"), leadToken)).andExpect(status().isOk());
		mvc.perform(bearer(delete("/api/teams/" + teamA + "/members/" + mgrLead.getId()), adminToken))
				.andExpect(status().isNoContent());
		mvc.perform(bearer(get("/api/teams"), leadToken)).andExpect(status().isUnauthorized());

		// demoted from LEAD: old token dies; promoting does not cut anything off
		addMember(teamB, mgrOther, "LEAD");
		Thread.sleep(1100);
		String otherToken = login(mgrOther.getEmail());
		String memberToken = login(m2.getEmail());
		mvc.perform(bearer(json(put("/api/teams/" + teamA + "/members/" + m2.getId()), "{\"teamRole\":\"LEAD\"}"),
				adminToken)).andExpect(status().isOk());
		mvc.perform(bearer(get("/api/teams"), memberToken)).andExpect(status().isOk()); // promotion: no cut-off
		mvc.perform(bearer(json(put("/api/teams/" + teamB + "/members/" + mgrOther.getId()),
				"{\"teamRole\":\"MEMBER\"}"), adminToken)).andExpect(status().isOk());
		mvc.perform(bearer(get("/api/teams"), otherToken)).andExpect(status().isUnauthorized());
		// plain member removal: no cut-off
		mvc.perform(bearer(delete("/api/teams/" + teamA + "/members/" + member.getId()), adminToken))
				.andExpect(status().isNoContent());
	}

	@Test
	void deletingATeamCutsOffItsLeadsButNotItsPlainMembers() throws Exception {
		Thread.sleep(1100);
		String leadToken = login(mgrLead.getEmail());
		String memberToken = login(member.getEmail());
		mvc.perform(bearer(delete("/api/teams/" + teamA), login(admin.getEmail()))).andExpect(status().isNoContent());
		mvc.perform(bearer(get("/api/teams"), leadToken)).andExpect(status().isUnauthorized());
		mvc.perform(bearer(get("/api/teams"), memberToken)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(0));
	}

	@Test
	void teamAndMemberListsUseAConstantNumberOfQueriesAndThePrincipalAddsOne() throws Exception {
		String adminToken = login(admin.getEmail());
		String memberToken = login(member.getEmail());
		Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		try {
			long teamsSmall = statements(stats, "/api/teams", adminToken);
			long membersSmall = statements(stats, "/api/teams/" + teamA + "/members", memberToken);
			long meSmall = statements(stats, "/api/auth/me", memberToken);
			for (int i = 0; i < 12; i++) {
				long team = insertTeam("Extra " + i);
				addMember(team, member, "MEMBER");
				User extra = createUser("extra" + i + "@example.com", Role.MEMBER);
				addMember(teamA, extra, "MEMBER");
			}
			assertThat(statements(stats, "/api/teams", adminToken)).isEqualTo(teamsSmall).isLessThanOrEqualTo(5);
			assertThat(statements(stats, "/api/teams/" + teamA + "/members", memberToken)).isEqualTo(membersSmall)
					.isLessThanOrEqualTo(5);
			// principal = user row + one membership query, plus the endpoint's own read; constant in the number of teams
			assertThat(statements(stats, "/api/auth/me", memberToken)).isEqualTo(meSmall).isLessThanOrEqualTo(3);
		}
		finally {
			stats.setStatisticsEnabled(false);
		}
	}

	private long statements(Statistics stats, String path, String token) throws Exception {
		stats.clear();
		mvc.perform(bearer(get(path).param("size", "100"), token)).andExpect(status().isOk());
		return stats.getPrepareStatementCount();
	}

	@Test
	void responsesNeverExposeInternalFlagsOrSecrets() throws Exception {
		String token = login(admin.getEmail());
		for (String path : new String[] { "/api/teams", "/api/teams/" + teamA, "/api/teams/" + teamA + "/members",
				"/api/teams/" + teamA + "/members/" + member.getId(), "/api/users/" + member.getId() + "/teams" }) {
			String body = mvc.perform(bearer(get(path), token)).andExpect(status().isOk()).andReturn().getResponse()
					.getContentAsString();
			assertThat(body).doesNotContain("passwordHash", "tokenHash", "\"active\"", "\"deleted\"", "password");
		}
	}

	@Test
	void invitationLocationHeaderPointsAtAnExistingResource() throws Exception {
		String adminToken = login(admin.getEmail());
		MvcResult created = mvc.perform(bearer(json(post("/api/invitations"),
				"{\"email\":\"new@example.com\",\"role\":\"MEMBER\"}"), adminToken)).andExpect(status().isCreated())
				.andReturn();
		String location = created.getResponse().getHeader("Location");
		assertThat(location).endsWith("/api/invitations/" + field(created, "$.data.id"));
		String body = mvc.perform(bearer(get(location), adminToken)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.email").value("new@example.com")).andReturn().getResponse()
				.getContentAsString();
		assertThat(body).doesNotContain("acceptUrl", "tokenHash", "token\"");
		// a Manager sees only their own; a Member has no access
		mvc.perform(bearer(get(location), login(mgrLead.getEmail()))).andExpect(status().isForbidden());
		mvc.perform(bearer(get(location), login(member.getEmail()))).andExpect(status().isForbidden());
		mvc.perform(bearer(get("/api/invitations/999999"), adminToken)).andExpect(status().isNotFound());
		mvc.perform(get(location)).andExpect(status().isUnauthorized());
		mvc.perform(bearer(delete(location), adminToken)).andExpect(status().isNoContent());
		mvc.perform(bearer(get(location), adminToken)).andExpect(status().isNotFound());
	}

}
