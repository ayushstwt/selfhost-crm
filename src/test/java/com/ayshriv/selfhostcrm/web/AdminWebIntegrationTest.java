package com.ayshriv.selfhostcrm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.support.ApiTestSupport;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The admin pages: authorization per role, CSRF, HTMX partials, row actions, one-time invite link, errors. */
class AdminWebIntegrationTest extends ApiTestSupport {

	private static final String HX_HEADERS = "hx-headers=\"{&quot;X-CSRF-TOKEN&quot;:&quot;";

	User admin;

	User manager; // MANAGER, LEAD of team A

	User otherManager; // MANAGER, in no team

	User member; // MEMBER, plain member of team A

	User outsider; // MEMBER, in no team

	long teamA;

	long teamB;

	@BeforeEach
	void seed() {
		admin = createUser("admin@example.com", Role.ADMIN);
		manager = createUser("manager@example.com", Role.MANAGER);
		otherManager = createUser("other@example.com", Role.MANAGER);
		member = createUser("member@example.com", Role.MEMBER);
		outsider = createUser("outsider@example.com", Role.MEMBER);
		teamA = insertTeam("Team A");
		teamB = insertTeam("Team B");
		addMember(teamA, manager, "LEAD");
		addMember(teamA, member, "MEMBER");
	}

	private long insertTeam(String name) {
		return jdbc.queryForObject(
				"insert into teams (created_on, updated_on, name) values (now(), now(), ?) returning id", Long.class,
				name);
	}

	private void addMember(long team, User user, String role) {
		jdbc.update("insert into team_members (created_on, updated_on, team_id, user_id, team_role) "
				+ "values (now(), now(), ?, ?, ?)", team, user.getId(), role);
	}

	private MockHttpSession session(User user) throws Exception {
		MockHttpSession session = (MockHttpSession) mvc.perform(post("/login").with(csrf())
				.param("username", user.getEmail()).param("password", PASSWORD)).andReturn().getRequest()
				.getSession(false);
		assertThat(session).isNotNull();
		return session;
	}

	private static MockHttpServletRequestBuilder htmx(MockHttpServletRequestBuilder builder) {
		return builder.header("HX-Request", "true");
	}

	private String body(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request).andReturn().getResponse().getContentAsString();
	}

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	// ---- authorization of the pages ----

	private record Page(String name, String path, int anonymous, int member, int manager, int admin) {

		@Override
		public String toString() {
			return name;
		}

	}

	static Stream<Arguments> pages() {
		return Stream.of(
				new Page("users", "/admin/users", 302, 403, 200, 200),
				new Page("invitation list", "/admin/invitations", 302, 403, 200, 200),
				new Page("user search", "/admin/users/search?q=a", 302, 403, 200, 200),
				new Page("teams", "/admin/teams", 302, 200, 200, 200),
				new Page("audit", "/admin/audit", 302, 403, 403, 200),
				new Page("unknown admin route (deny by default)", "/admin/nothing-here", 302, 403, 403, 404))
				.map(Arguments::of);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("pages")
	void pageAuthorizationPerRole(Page p) throws Exception {
		User[] actors = { null, member, manager, admin };
		int[] expected = { p.anonymous(), p.member(), p.manager(), p.admin() };
		for (int i = 0; i < actors.length; i++) {
			MockHttpServletRequestBuilder request = get(p.path());
			if (actors[i] != null) {
				request = request.session(session(actors[i]));
			}
			assertThat(mvc.perform(request).andReturn().getResponse().getStatus())
					.as("%s as %s", p.name(), actors[i] == null ? "anonymous" : actors[i].getEmail())
					.isEqualTo(expected[i]);
		}
	}

	@Test
	void anonymousIsRedirectedToLogin() throws Exception {
		mvc.perform(get("/admin/users")).andExpect(redirectedUrl("/login"));
		mvc.perform(post("/admin/teams").with(csrf()).param("name", "X")).andExpect(redirectedUrl("/login"));
	}

	@Test
	void forbiddenAndNotFoundPagesAreFriendlyAndNeverShowInternals() throws Exception {
		MockHttpSession memberSession = session(member);
		for (String path : new String[] { "/admin/users", "/admin/audit", "/admin/teams/" + teamB,
				"/admin/teams/999999" }) {
			MvcResult r = mvc.perform(get(path).session(memberSession)).andExpect(status().isForbidden()).andReturn();
			String html = r.getResponse().getContentAsString();
			assertThat(html).as(path).contains("403").containsPattern("Access denied|not allowed").doesNotContain("Exception", "at com.ayshriv",
					"Whitelabel", "stackTrace");
		}
		String notFound = mvc.perform(get("/admin/teams/999999").session(session(admin)))
				.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
		assertThat(notFound).contains("404", "Team not found").doesNotContain("Exception", "at com.ayshriv");
	}

	// ---- CSRF and HTMX wiring ----

	@Test
	void everyAdminPageCarriesTheCsrfTokenForHtmxAndNeverLeaksInternals() throws Exception {
		MockHttpSession adminSession = session(admin);
		for (String path : new String[] { "/admin/users", "/admin/teams", "/admin/teams/" + teamA, "/admin/audit",
				"/" }) {
			String html = mvc.perform(get(path).session(adminSession)).andExpect(status().isOk()).andReturn()
					.getResponse().getContentAsString();
			assertThat(html).as(path).contains(HX_HEADERS).contains("/webjars/htmx.org/2.0.11/dist/htmx.min.js");
			assertThat(html).as(path).doesNotContain("passwordHash", "tokenHash", "password_hash", "is_deleted",
					"$2a$");
		}
	}

	@Test
	void everyPostNeedsTheCsrfToken() throws Exception {
		MockHttpSession s = session(admin);
		long m = member.getId();
		String[][] posts = { { "/admin/users/" + m + "/enabled", "enabled=false" },
				{ "/admin/users/" + m + "/role", "role=MANAGER" }, { "/admin/users/" + m + "/delete", "" },
				{ "/admin/invitations", "email=x@example.com&role=MEMBER" },
				{ "/admin/invitations/1/revoke", "" }, { "/admin/teams", "name=NoCsrf" },
				{ "/admin/teams/" + teamA + "/rename", "name=NoCsrf" }, { "/admin/teams/" + teamA + "/delete", "" },
				{ "/admin/teams/" + teamA + "/members", "userId=" + outsider.getId() + "&teamRole=MEMBER" },
				{ "/admin/teams/" + teamA + "/members/" + m + "/role", "teamRole=LEAD" },
				{ "/admin/teams/" + teamA + "/members/" + m + "/remove", "" } };
		for (String[] p : posts) {
			MockHttpServletRequestBuilder request = post(p[0]).session(s)
					.contentType("application/x-www-form-urlencoded").content(p[1]);
			assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).as(p[0]).isEqualTo(403);
		}
		assertThat(count("select count(*) from teams where name = 'NoCsrf'")).isZero();
		assertThat(count("select count(*) from users where id = ? and enabled = true and role = 'MEMBER'", m))
				.isEqualTo(1);
		assertThat(count("select count(*) from team_members where team_id = ?", teamA)).isEqualTo(2);
		// the HTMX way (token in a header) works
		mvc.perform(htmx(post("/admin/teams/" + teamA + "/members/" + m + "/role").session(s)
				.with(csrf().asHeader()).param("teamRole", "LEAD"))).andExpect(status().isOk());
	}

	@Test
	void htmxRequestsGetFragmentsAndPlainRequestsGetFullPages() throws Exception {
		MockHttpSession s = session(admin);
		for (String path : new String[] { "/admin/users", "/admin/teams", "/admin/audit" }) {
			String fragment = body(htmx(get(path).session(s)));
			assertThat(fragment).as(path).contains("<table").doesNotContain("<html", "<nav", "<body");
			assertThat(body(get(path).session(s))).as(path).contains("<html", "<nav", HX_HEADERS);
		}
		String members = body(htmx(get("/admin/teams/" + teamA + "/members").session(s)));
		assertThat(members).contains("Team role", "member@example.com").doesNotContain("<html");
	}

	@Test
	void navigationShowsOnlyWhatTheRoleMayUse() throws Exception {
		String adminNav = body(get("/admin/teams").session(session(admin)));
		assertThat(adminNav).contains("/admin/users", "/admin/audit", "/admin/teams");
		String managerNav = body(get("/admin/teams").session(session(manager)));
		assertThat(managerNav).contains("/admin/users", "/admin/teams").doesNotContain("/admin/audit");
		String memberNav = body(get("/admin/teams").session(session(member)));
		assertThat(memberNav).contains("/admin/teams").doesNotContain("/admin/users", "/admin/audit");
	}

	// ---- users ----

	@Test
	void userRowActionsFollowThePermissionMatrix() throws Exception {
		String adminHtml = body(get("/admin/users").session(session(admin)));
		assertThat(adminHtml).contains("Set role", "Delete", "Disable");
		String managerHtml = body(get("/admin/users").session(session(manager)));
		assertThat(managerHtml).contains("Disable").doesNotContain("Set role", "/delete", "Delete");
		// the manager cannot toggle the admin row, only member rows
		assertThat(managerHtml).contains("/admin/users/" + member.getId() + "/enabled")
				.doesNotContain("/admin/users/" + admin.getId() + "/enabled")
				.doesNotContain("/admin/users/" + otherManager.getId() + "/enabled");
		// nobody sees actions on their own row
		assertThat(adminHtml).doesNotContain("/admin/users/" + admin.getId() + "/enabled");
	}

	@Test
	void userListFiltersAndLenientParameters() throws Exception {
		MockHttpSession s = session(admin);
		String filtered = body(htmx(get("/admin/users").param("role", "MANAGER").param("q", "other").session(s)));
		assertThat(filtered).contains("other@example.com").doesNotContain("member@example.com");
		String disabled = body(htmx(get("/admin/users").param("enabled", "false").session(s)));
		assertThat(disabled).contains("No users match");
		// junk parameters never become a 500
		mvc.perform(get("/admin/users").param("role", "NOPE").param("enabled", "maybe").param("page", "x").session(s))
				.andExpect(status().isOk());
		mvc.perform(get("/admin/audit").param("action", "NOPE").param("from", "yesterday").param("actorId", "z")
				.session(s)).andExpect(status().isOk());
	}

	@Test
	void adminCanDisableChangeRoleAndDeleteWhileManagerCanOnlyDisableMembers() throws Exception {
		MockHttpSession a = session(admin);
		long m = member.getId();
		mvc.perform(htmx(post("/admin/users/" + m + "/enabled").session(a).with(csrf()).param("enabled", "false")))
				.andExpect(status().isOk()).andExpect(content().string(containsString("Disabled")))
				.andExpect(content().string(not(containsString("<html"))));
		assertThat(count("select count(*) from users where id = ? and enabled = false", m)).isEqualTo(1);
		mvc.perform(htmx(post("/admin/users/" + m + "/role").session(a).with(csrf()).param("role", "MANAGER")))
				.andExpect(status().isOk());
		assertThat(count("select count(*) from users where id = ? and role = 'MANAGER'", m)).isEqualTo(1);
		// self-protection comes back inline
		mvc.perform(htmx(post("/admin/users/" + admin.getId() + "/enabled").session(a).with(csrf())
				.param("enabled", "false"))).andExpect(status().isForbidden())
				.andExpect(header().string("HX-Retarget", "#flash"))
				.andExpect(content().string(containsString("your own account")));
		mvc.perform(htmx(post("/admin/users/" + outsider.getId() + "/delete").session(a).with(csrf())))
				.andExpect(status().isOk()).andExpect(content().string(""));
		assertThat(count("select count(*) from users where id = ? and is_deleted = true", outsider.getId()))
				.isEqualTo(1);

		MockHttpSession mgr = session(manager);
		mvc.perform(htmx(post("/admin/users/" + admin.getId() + "/enabled").session(mgr).with(csrf())
				.param("enabled", "false"))).andExpect(status().isForbidden());
		mvc.perform(htmx(post("/admin/users/" + otherManager.getId() + "/delete").session(mgr).with(csrf())))
				.andExpect(status().isForbidden());
		mvc.perform(post("/admin/users/" + otherManager.getId() + "/role").session(mgr).with(csrf())
				.param("role", "MEMBER")).andExpect(status().isForbidden());
		assertThat(count("select count(*) from users where id = ? and is_deleted = false and role = 'MANAGER'",
				otherManager.getId())).isEqualTo(1);
	}

	@Test
	void userSearchOffersOnlyMemberAccountsToAManager() throws Exception {
		String forManager = body(htmx(get("/admin/users/search").param("q", "").session(session(manager))));
		assertThat(forManager).contains("member@example.com").doesNotContain("admin@example.com",
				"other@example.com");
		String forAdmin = body(htmx(get("/admin/users/search").param("q", "").session(session(admin))));
		assertThat(forAdmin).contains("member@example.com", "admin@example.com", "other@example.com");
	}

	// ---- invitations ----

	@Test
	void inviteLinkIsShownOnceAndNeverAgain() throws Exception {
		MockHttpSession s = session(admin);
		MvcResult r = mvc.perform(htmx(post("/admin/invitations").session(s).with(csrf())
				.param("email", "new.hire@example.com").param("role", "MEMBER"))).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", containsString("no-store")))
				.andExpect(header().string("HX-Trigger", "invitationsChanged"))
				.andExpect(content().string(containsString("shown only once"))).andReturn();
		Matcher m = Pattern.compile("/invite/([A-Za-z0-9_-]{43})").matcher(r.getResponse().getContentAsString());
		assertThat(m.find()).isTrue();
		String token = m.group(1);
		// only the hash is stored; the raw token is in no later page, fragment, session attribute or audit entry
		assertThat(count("select count(*) from invitations where token_hash = ?", token)).isZero();
		for (String path : new String[] { "/admin/users", "/admin/invitations", "/admin/audit", "/admin/teams" }) {
			assertThat(body(get(path).session(s))).as(path).doesNotContain(token);
		}
		assertThat(body(htmx(get("/admin/invitations").session(s)))).contains("new.hire@example.com")
				.doesNotContain(token);
		java.util.Collections.list(s.getAttributeNames()).forEach(
				name -> assertThat(String.valueOf(s.getAttribute(name))).doesNotContain(token));
		mvc.perform(get("/invite/" + token)).andExpect(status().isOk());
		// revoke from the list
		long id = jdbc.queryForObject("select id from invitations where email = 'new.hire@example.com'", Long.class);
		mvc.perform(htmx(post("/admin/invitations/" + id + "/revoke").session(s).with(csrf())))
				.andExpect(status().isOk()).andExpect(content().string(containsString("No pending invitations")));
		mvc.perform(get("/invite/" + token)).andExpect(status().isBadRequest());
	}

	@Test
	void inviteFormValidationAndRoleRulesShowInline() throws Exception {
		MockHttpSession a = session(admin);
		mvc.perform(htmx(post("/admin/invitations").session(a).with(csrf()).param("email", "not-an-email")
				.param("role", "MEMBER"))).andExpect(status().isUnprocessableEntity())
				.andExpect(content().string(containsString("Enter a valid email address")));
		mvc.perform(htmx(post("/admin/invitations").session(a).with(csrf()).param("email", "member@example.com")
				.param("role", "MEMBER"))).andExpect(status().isConflict())
				.andExpect(content().string(containsString("already exists")));
		MockHttpSession mgr = session(manager);
		mvc.perform(htmx(post("/admin/invitations").session(mgr).with(csrf()).param("email", "boss@example.com")
				.param("role", "ADMIN"))).andExpect(status().isForbidden())
				.andExpect(content().string(containsString("not allowed")));
		assertThat(count("select count(*) from invitations")).isZero();
		// a Manager's form only offers MEMBER
		String managerPage = body(get("/admin/users").session(mgr));
		String inviteSelect = managerPage.substring(managerPage.indexOf("id=\"invite-role\""));
		inviteSelect = inviteSelect.substring(0, inviteSelect.indexOf("</select>"));
		assertThat(inviteSelect).contains("MEMBER").doesNotContain("ADMIN", "MANAGER");
	}

	// ---- teams ----

	@Test
	void adminCreatesRenamesAndDeletesTeamsWithInlineErrors() throws Exception {
		MockHttpSession a = session(admin);
		MvcResult created = mvc.perform(htmx(post("/admin/teams").session(a).with(csrf()).param("name", " Ops ")
				.param("description", "Operations"))).andExpect(status().isOk()).andReturn();
		long id = jdbc.queryForObject("select id from teams where name = 'Ops'", Long.class);
		assertThat(created.getResponse().getHeader("HX-Redirect")).isEqualTo("/admin/teams/" + id);
		// without HTMX: ordinary redirect
		mvc.perform(post("/admin/teams").session(a).with(csrf()).param("name", "Finance"))
				.andExpect(status().is3xxRedirection());
		mvc.perform(htmx(post("/admin/teams").session(a).with(csrf()).param("name", "  ")))
				.andExpect(status().isUnprocessableEntity()).andExpect(content().string(containsString("Name is required")));
		mvc.perform(htmx(post("/admin/teams").session(a).with(csrf()).param("name", "ops")))
				.andExpect(status().isConflict()).andExpect(content().string(containsString("already exists")))
				.andExpect(content().string(containsString("value=\"ops\"")));
		mvc.perform(htmx(post("/admin/teams/" + id + "/rename").session(a).with(csrf()).param("name", "TEAM A")))
				.andExpect(status().isConflict()).andExpect(content().string(containsString("already exists")));
		mvc.perform(htmx(post("/admin/teams/" + id + "/rename").session(a).with(csrf()).param("name", "Operations")
				.param("description", "")))
				.andExpect(status().isOk()).andExpect(content().string(containsString("Operations")));
		assertThat(count("select count(*) from teams where name = 'Operations'")).isEqualTo(1);
		mvc.perform(htmx(post("/admin/teams/" + id + "/delete").session(a).with(csrf()))).andExpect(status().isOk())
				.andExpect(header().string("HX-Redirect", "/admin/teams"));
		assertThat(count("select count(*) from teams where id = ? and is_deleted = true", id)).isEqualTo(1);
	}

	@Test
	void managersAndMembersCannotCreateRenameOrDeleteTeamsAndDoNotSeeTheControls() throws Exception {
		for (User u : List.of(manager, member)) {
			MockHttpSession s = session(u);
			mvc.perform(htmx(post("/admin/teams").session(s).with(csrf()).param("name", "Nope")))
					.andExpect(status().isForbidden());
			mvc.perform(htmx(post("/admin/teams/" + teamA + "/rename").session(s).with(csrf()).param("name", "Nope")))
					.andExpect(status().isForbidden());
			mvc.perform(htmx(post("/admin/teams/" + teamA + "/delete").session(s).with(csrf())))
					.andExpect(status().isForbidden());
			assertThat(body(get("/admin/teams").session(s))).doesNotContain("New team");
			assertThat(body(get("/admin/teams/" + teamA).session(s))).doesNotContain("Delete team", "Rename", "/rename");
		}
		assertThat(count("select count(*) from teams where is_deleted = false")).isEqualTo(2);
	}

	@Test
	void teamListsShowOnlyWhatTheRoleMaySee() throws Exception {
		String forMember = body(get("/admin/teams").session(session(member)));
		assertThat(forMember).contains("Team A").doesNotContain("Team B");
		String forOtherManager = body(get("/admin/teams").session(session(otherManager)));
		assertThat(forOtherManager).contains("No teams to show");
		String forAdmin = body(get("/admin/teams").session(session(admin)));
		assertThat(forAdmin).contains("Team A", "Team B");
		mvc.perform(get("/admin/teams/" + teamB).session(session(manager))).andExpect(status().isForbidden());
	}

	@Test
	void teamDetailControlsFollowTheRole() throws Exception {
		String asAdmin = body(get("/admin/teams/" + teamA).session(session(admin)));
		assertThat(asAdmin).contains("Add a member", "Delete team", "/rename", "Remove");
		String asLead = body(get("/admin/teams/" + teamA).session(session(manager)));
		assertThat(asLead).contains("Add a member").contains("/members/" + member.getId() + "/remove")
				.doesNotContain("Delete team", "/rename")
				.doesNotContain("/members/" + manager.getId() + "/remove"); // cannot remove themselves
		String asMember = body(get("/admin/teams/" + teamA).session(session(member)));
		assertThat(asMember).contains("manager@example.com".isEmpty() ? "" : "Members")
				.doesNotContain("Add a member", "Delete team", "/remove", "manager@example.com");
	}

	@Test
	void membersCanBeAddedChangedAndRemovedFromTheTeamPage() throws Exception {
		MockHttpSession lead = session(manager);
		String base = "/admin/teams/" + teamA + "/members";
		mvc.perform(htmx(post(base).session(lead).with(csrf()).param("userId", String.valueOf(outsider.getId()))
				.param("teamRole", "MEMBER"))).andExpect(status().isOk())
				.andExpect(content().string(containsString("outsider@example.com")));
		mvc.perform(htmx(post(base + "/" + outsider.getId() + "/role").session(lead).with(csrf())
				.param("teamRole", "LEAD"))).andExpect(status().isOk());
		assertThat(count("select count(*) from team_members where user_id = ? and team_role = 'LEAD'",
				outsider.getId())).isEqualTo(1);
		mvc.perform(htmx(post(base + "/" + outsider.getId() + "/remove").session(lead).with(csrf())))
				.andExpect(status().isOk()).andExpect(content().string(not(containsString("outsider@example.com"))));
		// rules come back inline (flash), status preserved
		mvc.perform(htmx(post(base).session(lead).with(csrf()).param("userId", String.valueOf(admin.getId()))
				.param("teamRole", "MEMBER"))).andExpect(status().isForbidden())
				.andExpect(header().string("HX-Retarget", "#flash"))
				.andExpect(content().string(containsString("not allowed")));
		mvc.perform(htmx(post(base).session(lead).with(csrf()).param("userId", "abc").param("teamRole", "MEMBER")))
				.andExpect(status().isBadRequest());
		mvc.perform(htmx(post(base).session(lead).with(csrf()).param("userId", "999999").param("teamRole", "MEMBER")))
				.andExpect(status().isNotFound());
		// a plain member and a manager of another team cannot
		for (User u : List.of(member, otherManager)) {
			mvc.perform(htmx(post(base).session(session(u)).with(csrf()).param("userId",
					String.valueOf(outsider.getId())).param("teamRole", "MEMBER"))).andExpect(status().isForbidden());
		}
		assertThat(count("select count(*) from team_members where user_id = ?", outsider.getId())).isZero();
		// without HTMX the error is the friendly page
		String page = mvc.perform(post(base).session(lead).with(csrf()).param("userId", String.valueOf(admin.getId()))
				.param("teamRole", "MEMBER")).andExpect(status().isForbidden()).andReturn().getResponse()
				.getContentAsString();
		assertThat(page).contains("<html").doesNotContain("Exception");
	}

	// ---- audit ----

	@Test
	void auditPageFiltersByTargetTypeActionActorAndDates() throws Exception {
		MockHttpSession a = session(admin);
		mvc.perform(htmx(post("/admin/teams").session(a).with(csrf()).param("name", "Audited")))
				.andExpect(status().isOk());
		long id = jdbc.queryForObject("select id from teams where name = 'Audited'", Long.class);
		mvc.perform(htmx(post("/admin/teams/" + id + "/members").session(a).with(csrf())
				.param("userId", String.valueOf(member.getId())).param("teamRole", "LEAD"))).andExpect(status().isOk());
		String team = body(htmx(get("/admin/audit").param("targetType", "TEAM").session(a)));
		assertThat(team).contains("TEAM_CREATED", "TEAM_MEMBER_ADDED", "TEAM #" + id, "User #" + member.getId())
				.doesNotContain("LOGIN");
		assertThat(body(htmx(get("/admin/audit").param("action", "TEAM_CREATED").session(a))))
				.contains("TEAM_CREATED").doesNotContain("TEAM_MEMBER_ADDED");
		assertThat(body(htmx(get("/admin/audit").param("actorId", String.valueOf(member.getId())).session(a))))
				.contains("No entries match");
		String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
		assertThat(body(htmx(get("/admin/audit").param("from", today).param("to", today).session(a))))
				.contains("TEAM_CREATED");
		assertThat(body(htmx(get("/admin/audit").param("to", "2000-01-01").session(a)))).contains("No entries match");
		mvc.perform(get("/admin/audit").param("from", "2030-01-02").param("to", "2030-01-01").session(a))
				.andExpect(status().isBadRequest());
	}

}
