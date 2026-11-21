package com.ayshriv.selfhostcrm.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ayshriv.selfhostcrm.TestcontainersConfiguration;
import com.ayshriv.selfhostcrm.domain.UserAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** V6 constraints: email reuse after soft delete, one pending invitation per email, audit CHECKs match the enums. */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@SpringBootTest
class RbacSchemaIntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void clean() {
		jdbc.execute("delete from user_log");
		jdbc.execute("delete from invitations");
		jdbc.execute("delete from refresh_tokens");
		jdbc.execute("delete from team_members");
		jdbc.execute("delete from teams");
		jdbc.execute("delete from users");
	}

	private long insertUser(String email, boolean deleted) {
		return jdbc.queryForObject("""
				insert into users (created_on, updated_on, is_deleted, email, display_name, password_hash, role)
				values (now(), now(), ?, ?, 'n', 'h', 'MEMBER') returning id""", Long.class, deleted, email);
	}

	@Test
	void emailCanBeReusedAfterSoftDeleteButNotWhileActive() {
		insertUser("a@example.com", true);
		insertUser("a@example.com", false);
		assertThatThrownBy(() -> insertUser("a@example.com", false))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void everyUserActionIsAcceptedByTheCheckConstraint() {
		long user = insertUser("b@example.com", false);
		for (UserAction action : UserAction.values()) {
			jdbc.update("""
					insert into user_log (created_on, updated_on, user_id, action, sub_action)
					values (now(), now(), ?, ?, 'SUCCESS')""", user, action.name());
		}
		assertThat(jdbc.queryForObject("select count(*) from user_log", Integer.class))
				.isEqualTo(UserAction.values().length);
		assertThatThrownBy(() -> jdbc.update("""
				insert into user_log (created_on, updated_on, user_id, action, sub_action)
				values (now(), now(), ?, 'NOT_AN_ACTION', 'SUCCESS')""", user))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void targetTypeAndIdMustBeSetTogether() {
		long user = insertUser("c@example.com", false);
		assertThatThrownBy(() -> jdbc.update("""
				insert into user_log (created_on, updated_on, user_id, action, sub_action, target_type)
				values (now(), now(), ?, 'USER_UPDATED', 'SUCCESS', 'USER')""", user))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void teamNamesAreUniqueCaseInsensitivelyAmongLiveTeamsOnly() {
		insertTeam("Sales", true);
		insertTeam("Sales", false);
		assertThatThrownBy(() -> insertTeam("SALES", false)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertTeam("  ", false)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void membershipIsUniquePerTeamAndUserAndRoleIsChecked() {
		long user = insertUser("m@example.com", false);
		long team = insertTeam("Ops", false);
		String sql = "insert into team_members (created_on, updated_on, team_id, user_id, team_role) values (now(), now(), ?, ?, ?)";
		jdbc.update(sql, team, user, "LEAD");
		assertThatThrownBy(() -> jdbc.update(sql, team, user, "MEMBER"))
				.isInstanceOf(DataIntegrityViolationException.class);
		long other = insertUser("o@example.com", false);
		assertThatThrownBy(() -> jdbc.update(sql, team, other, "BOSS")).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void teamTargetTypeIsAcceptedAndRelatedUserNeedsATarget() {
		long user = insertUser("t@example.com", false);
		jdbc.update("insert into user_log (created_on, updated_on, user_id, action, sub_action, target_type, target_id, related_user_id) values (now(), now(), ?, 'TEAM_MEMBER_ADDED', 'SUCCESS', 'TEAM', 1, ?)", user, user);
		assertThatThrownBy(() -> jdbc.update("insert into user_log (created_on, updated_on, user_id, action, sub_action, related_user_id) values (now(), now(), ?, 'TEAM_MEMBER_ADDED', 'SUCCESS', ?)", user, user))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private long insertTeam(String name, boolean deleted) {
		return jdbc.queryForObject("insert into teams (created_on, updated_on, is_deleted, name) values (now(), now(), ?, ?) returning id", Long.class, deleted, name);
	}

	@Test
	void onlyOnePendingInvitationPerEmail() {
		long inviter = insertUser("admin@example.com", false);
		String sql = """
				insert into invitations (created_on, updated_on, email, role, invited_by, token_hash, expires_at, revoked_at)
				values (now(), now(), 'new@example.com', 'MEMBER', ?, ?, now() + interval '7 days', %s)""";
		jdbc.update(sql.formatted("null"), inviter, "h1");
		assertThatThrownBy(() -> jdbc.update(sql.formatted("null"), inviter, "h2"))
				.isInstanceOf(DataIntegrityViolationException.class);
		jdbc.update(sql.formatted("now()"), inviter, "h3"); // revoked ones do not count
	}

}
