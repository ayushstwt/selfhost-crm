package com.ayshriv.selfhostcrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.support.ApiTestSupport;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class UserManagementIntegrationTest extends ApiTestSupport {

	@Autowired
	EntityManagerFactory emf;

	private record Endpoint(String name, String method, String path, String body, int anonymous, int member,
			int manager, int admin) {

		@Override
		public String toString() {
			return name;
		}

	}

	static Stream<Arguments> matrix() {
		return Stream.of(
				new Endpoint("list users", "GET", "/api/users", null, 401, 403, 200, 200),
				new Endpoint("get user", "GET", "/api/users/{t}", null, 401, 403, 200, 200),
				new Endpoint("update user", "PATCH", "/api/users/{t}", "{\"displayName\":\"Renamed\"}", 401, 403, 200, 200),
				new Endpoint("change role", "PATCH", "/api/users/{t}/role", "{\"role\":\"MANAGER\"}", 401, 403, 403, 200),
				new Endpoint("delete user", "DELETE", "/api/users/{t}", null, 401, 403, 403, 204),
				new Endpoint("unlisted route (deny by default)", "GET", "/api/not-a-route", null, 401, 403, 403, 404),
				new Endpoint("my activity", "GET", "/api/users/me/activity", null, 401, 200, 200, 200),
				new Endpoint("auth me", "GET", "/api/auth/me", null, 401, 200, 200, 200)).map(Arguments::of);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("matrix")
	void roleMatrix(Endpoint e) throws Exception {
		createUser("admin@example.com", Role.ADMIN);
		createUser("manager@example.com", Role.MANAGER);
		createUser("member@example.com", Role.MEMBER);
		User target = createUser("target@example.com", Role.MEMBER);
		String[] actors = { null, "member@example.com", "manager@example.com", "admin@example.com" };
		int[] expected = { e.anonymous(), e.member(), e.manager(), e.admin() };
		for (int i = 0; i < actors.length; i++) {
			// the mutating rows change the target, so re-create it for every actor
			jdbc.update("update users set role = 'MEMBER', is_deleted = false, display_name = 'target' "
					+ "where id = ?", target.getId());
			String token = actors[i] == null ? null : login(actors[i]);
			MockHttpServletRequestBuilder request = switch (e.method()) {
				case "GET" -> get(e.path().replace("{t}", String.valueOf(target.getId())));
				case "PATCH" -> patch(e.path().replace("{t}", String.valueOf(target.getId())));
				default -> delete(e.path().replace("{t}", String.valueOf(target.getId())));
			};
			if (e.body() != null) {
				request = json(request, e.body());
			}
			int actual = mvc.perform(bearer(request, token)).andReturn().getResponse().getStatus();
			assertThat(actual).as("%s as %s", e.name(), actors[i] == null ? "anonymous" : actors[i])
					.isEqualTo(expected[i]);
		}
	}

	@Test
	void managerCannotTouchAdminsOrManagersAndCannotChangeRoles() throws Exception {
		User admin = createUser("admin@example.com", Role.ADMIN);
		createUser("manager@example.com", Role.MANAGER);
		User otherManager = createUser("manager2@example.com", Role.MANAGER);
		User member = createUser("member@example.com", Role.MEMBER);
		String token = login("manager@example.com");

		for (User forbidden : List.of(admin, otherManager)) {
			mvc.perform(bearer(json(patch("/api/users/" + forbidden.getId()), "{\"displayName\":\"x\"}"), token))
					.andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("E013"));
			mvc.perform(bearer(json(patch("/api/users/" + forbidden.getId()), "{\"enabled\":false}"), token))
					.andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("E013"));
			mvc.perform(bearer(json(patch("/api/users/" + forbidden.getId() + "/role"), "{\"role\":\"MEMBER\"}"), token))
					.andExpect(status().isForbidden());
			mvc.perform(bearer(delete("/api/users/" + forbidden.getId()), token)).andExpect(status().isForbidden());
			// but may view them
			mvc.perform(bearer(get("/api/users/" + forbidden.getId()), token)).andExpect(status().isOk());
		}
		mvc.perform(bearer(json(patch("/api/users/" + member.getId() + "/role"), "{\"role\":\"MANAGER\"}"), token))
				.andExpect(status().isForbidden());
		// can edit and disable a member
		mvc.perform(bearer(json(patch("/api/users/" + member.getId()), "{\"displayName\":\"Renamed\",\"enabled\":false}"),
				token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.displayName").value("Renamed"))
				.andExpect(jsonPath("$.data.enabled").value(false));
	}

	@Test
	void nobodyChangesTheirOwnRoleDisablesOrDeletesThemselves() throws Exception {
		User admin = createUser("admin@example.com", Role.ADMIN);
		createUser("admin2@example.com", Role.ADMIN);
		User manager = createUser("manager@example.com", Role.MANAGER);
		String adminToken = login("admin@example.com");
		String managerToken = login("manager@example.com");

		mvc.perform(bearer(json(patch("/api/users/" + admin.getId() + "/role"), "{\"role\":\"MEMBER\"}"), adminToken))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("E015"));
		mvc.perform(bearer(json(patch("/api/users/" + admin.getId()), "{\"enabled\":false}"), adminToken))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("E015"));
		mvc.perform(bearer(delete("/api/users/" + admin.getId()), adminToken))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("E015"));
		mvc.perform(bearer(json(patch("/api/users/" + manager.getId()), "{\"enabled\":false}"), managerToken))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("E015"));
		// own display name is fine
		mvc.perform(bearer(json(patch("/api/users/" + admin.getId()), "{\"displayName\":\"Me\"}"), adminToken))
				.andExpect(status().isOk());
		mvc.perform(bearer(json(patch("/api/users/" + manager.getId()), "{\"displayName\":\"Me too\"}"), managerToken))
				.andExpect(status().isOk());
	}

	@Test
	void unknownUserIs404ForPermittedActorsAndNoUserIsExposedToMembers() throws Exception {
		createUser("admin@example.com", Role.ADMIN);
		User member = createUser("member@example.com", Role.MEMBER);
		createUser("other@example.com", Role.MEMBER);
		String admin = login("admin@example.com");
		mvc.perform(bearer(get("/api/users/999999"), admin)).andExpect(status().isNotFound());
		mvc.perform(bearer(delete("/api/users/999999"), admin)).andExpect(status().isNotFound());
		// a member asking for another user (or even themselves via this endpoint) gets 403
		String memberToken = login("member@example.com");
		mvc.perform(bearer(get("/api/users/" + member.getId()), memberToken)).andExpect(status().isForbidden());
	}

	@Test
	void emptyPatchIsAValidationError() throws Exception {
		User member = createUser("member@example.com", Role.MEMBER);
		createUser("admin@example.com", Role.ADMIN);
		mvc.perform(bearer(json(patch("/api/users/" + member.getId()), "{}"), login("admin@example.com")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("E005"));
	}

	@Test
	void roleChangeAndDisableAndDeleteWork() throws Exception {
		createUser("admin@example.com", Role.ADMIN);
		User member = createUser("member@example.com", Role.MEMBER);
		String admin = login("admin@example.com");

		mvc.perform(bearer(json(patch("/api/users/" + member.getId() + "/role"), "{\"role\":\"MANAGER\"}"), admin))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("MANAGER"));
		mvc.perform(bearer(json(patch("/api/users/" + member.getId() + "/role"), "{\"role\":\"NOPE\"}"), admin))
				.andExpect(status().isBadRequest());
		mvc.perform(bearer(delete("/api/users/" + member.getId()), admin)).andExpect(status().isNoContent());
		mvc.perform(bearer(get("/api/users/" + member.getId()), admin)).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("select is_deleted from users where id = ?", Boolean.class, member.getId()))
				.isTrue();
	}

	@Test
	void emailCanBeReusedAfterDeleteButDuplicateActiveEmailIsRejected() throws Exception {
		createUser("admin@example.com", Role.ADMIN);
		User member = createUser("member@example.com", Role.MEMBER);
		String admin = login("admin@example.com");

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> createUser("member@example.com", Role.MEMBER))
				.isInstanceOf(DataIntegrityViolationException.class);
		mvc.perform(bearer(delete("/api/users/" + member.getId()), admin)).andExpect(status().isNoContent());
		User again = createUser("member@example.com", Role.MEMBER);
		assertThat(again.getId()).isNotEqualTo(member.getId());
		// the new account can sign in; the deleted one never could again
		assertThat(loginResult("member@example.com").getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void listFiltersPaginationAndOrdering() throws Exception {
		createUser("admin@example.com", Role.ADMIN);
		createUser("anna@example.com", Role.MEMBER);
		Thread.sleep(5);
		createUser("bob_100%@example.com", Role.MEMBER);
		Thread.sleep(5);
		User carl = createUser("carl@example.com", Role.MANAGER);
		jdbc.update("update users set enabled = false where id = ?", carl.getId());
		String admin = login("admin@example.com");

		MvcResult all = mvc.perform(bearer(get("/api/users").param("size", "2"), admin)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.metadata.totalItems").value(4))
				.andExpect(jsonPath("$.metadata.totalPages").value(2))
				.andExpect(jsonPath("$.metadata.pageSize").value(2)).andReturn();
		// newest first
		assertThat((String) JsonPath.read(all.getResponse().getContentAsString(), "$.data[0].email"))
				.isEqualTo("carl@example.com");

		mvc.perform(bearer(get("/api/users").param("role", "MANAGER"), admin))
				.andExpect(jsonPath("$.metadata.totalItems").value(1));
		mvc.perform(bearer(get("/api/users").param("enabled", "false"), admin))
				.andExpect(jsonPath("$.metadata.totalItems").value(1));
		mvc.perform(bearer(get("/api/users").param("q", "ANNA"), admin))
				.andExpect(jsonPath("$.metadata.totalItems").value(1));
		// LIKE wildcards are literal
		mvc.perform(bearer(get("/api/users").param("q", "100%"), admin))
				.andExpect(jsonPath("$.metadata.totalItems").value(1));
		mvc.perform(bearer(get("/api/users").param("q", "%"), admin))
				.andExpect(jsonPath("$.metadata.totalItems").value(1));
		mvc.perform(bearer(get("/api/users").param("size", "101"), admin)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("E005"));
		mvc.perform(bearer(get("/api/users").param("role", "NOPE"), admin)).andExpect(status().isBadRequest());
	}

	@Test
	void responsesNeverExposeInternalFields() throws Exception {
		User admin = createUser("admin@example.com", Role.ADMIN);
		String token = login("admin@example.com");
		for (String path : List.of("/api/users", "/api/users/" + admin.getId(), "/api/auth/me")) {
			String body = mvc.perform(bearer(get(path), token)).andReturn().getResponse().getContentAsString();
			assertThat(body).doesNotContain("passwordHash", "password_hash", "tokenHash", "\"active\"", "\"deleted\"",
					"$2a$", "failedLoginCount", "lockedUntil", "accessChangedAt", "credentialsChangedAt");
		}
	}

	@Test
	void listUsesAConstantNumberOfQueries() throws Exception {
		createUser("admin@example.com", Role.ADMIN);
		for (int i = 0; i < 3; i++) {
			createUser("small" + i + "@example.com", Role.MEMBER);
		}
		String token = login("admin@example.com");
		Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		try {
			long small = statementsFor(stats, token);
			for (int i = 0; i < 30; i++) {
				createUser("big" + i + "@example.com", Role.MEMBER);
			}
			long large = statementsFor(stats, token);
			assertThat(large).isEqualTo(small).isLessThanOrEqualTo(4);
		}
		finally {
			stats.setStatisticsEnabled(false);
		}
	}

	private long statementsFor(Statistics stats, String token) throws Exception {
		stats.clear();
		mvc.perform(bearer(get("/api/users").param("size", "100"), token)).andExpect(status().isOk());
		return stats.getPrepareStatementCount();
	}

	@Test
	void mutualDemotionOfTheOnlyTwoAdminsCannotBothSucceed() throws Exception {
		for (int round = 0; round < 3; round++) {
			cleanDatabase();
			User a = createUser("a@example.com", Role.ADMIN);
			User b = createUser("b@example.com", Role.ADMIN);
			String tokenA = login("a@example.com");
			String tokenB = login("b@example.com");

			CountDownLatch start = new CountDownLatch(1);
			ExecutorService pool = Executors.newFixedThreadPool(2);
			try {
				Callable<Integer> aDemotesB = () -> {
					start.await();
					return mvc.perform(bearer(json(patch("/api/users/" + b.getId() + "/role"), "{\"role\":\"MEMBER\"}"),
							tokenA)).andReturn().getResponse().getStatus();
				};
				Callable<Integer> bDemotesA = () -> {
					start.await();
					return mvc.perform(bearer(json(patch("/api/users/" + a.getId() + "/role"), "{\"role\":\"MEMBER\"}"),
							tokenB)).andReturn().getResponse().getStatus();
				};
				List<Future<Integer>> results = new ArrayList<>(List.of(pool.submit(aDemotesB), pool.submit(bDemotesA)));
				start.countDown();
				List<Integer> statuses = new ArrayList<>();
				for (Future<Integer> f : results) {
					statuses.add(f.get());
				}
				assertThat(statuses).containsExactlyInAnyOrder(200, 409);
			}
			finally {
				pool.shutdownNow();
			}
			assertThat(jdbc.queryForObject("select count(*) from users where role = 'ADMIN' and enabled", Integer.class))
					.isEqualTo(1);
		}
	}

	@Test
	void lastAdminCannotBeDisabledOrDeletedEvenWhenAnotherAdminActsConcurrently() throws Exception {
		User a = createUser("a@example.com", Role.ADMIN);
		User b = createUser("b@example.com", Role.ADMIN);
		String tokenA = login("a@example.com");
		String tokenB = login("b@example.com");
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Integer> disable = pool.submit(() -> {
				start.await();
				return mvc.perform(bearer(json(patch("/api/users/" + b.getId()), "{\"enabled\":false}"), tokenA))
						.andReturn().getResponse().getStatus();
			});
			Future<Integer> delete = pool.submit(() -> {
				start.await();
				return mvc.perform(bearer(delete("/api/users/" + a.getId()), tokenB)).andReturn().getResponse()
						.getStatus();
			});
			start.countDown();
			List<Integer> statuses = List.of(disable.get(), delete.get());
			// exactly one of the two succeeds (200 or 204); the other is refused with 409
			assertThat(statuses.stream().filter(s -> s == 409)).hasSize(1);
			assertThat(statuses.stream().filter(s -> s == 200 || s == 204)).hasSize(1);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(jdbc.queryForObject(
				"select count(*) from users where role = 'ADMIN' and enabled and not is_deleted", Integer.class))
				.isEqualTo(1);
	}

}
