package com.ayshriv.selfhostcrm.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ayshriv.selfhostcrm.TestcontainersConfiguration;
import com.ayshriv.selfhostcrm.web.UserLogResponse;
import com.ayshriv.selfhostcrm.web.UserResponse;
import com.ayshriv.selfhostcrm.domain.RefreshToken;
import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.domain.UserAction;
import com.ayshriv.selfhostcrm.domain.UserLog;
import com.ayshriv.selfhostcrm.domain.UserSubAction;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@SpringBootTest
class PersistenceIntegrationTest {

	@Autowired
	UserRepository users;

	@Autowired
	RefreshTokenRepository tokens;

	@Autowired
	UserLogRepository logs;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JsonMapper json;

	@BeforeEach
	void clean() {
		jdbc.execute("delete from user_log");
		jdbc.execute("delete from invitations");
		jdbc.execute("delete from refresh_tokens");
		jdbc.execute("delete from users");
	}

	private User newUser(String email) {
		User u = new User();
		u.setEmail(email);
		u.setDisplayName("Jane Doe");
		u.setPasswordHash("$2a$10$hashhashhashhashhashhashhashhashhashhashhashhashhash");
		u.setRole(Role.ADMIN);
		return users.saveAndFlush(u);
	}

	@Test
	void emailIsStoredLowercaseAndUnique() {
		User u = newUser("  Jane@Example.COM ");
		assertThat(users.findByEmail("jane@example.com")).get().extracting(User::getId).isEqualTo(u.getId());
		assertThat(u.getCreatedOn()).isNotNull();
		assertThat(u.isActive()).isTrue();
		assertThatThrownBy(() -> newUser("JANE@example.com")).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void softDeletedUsersAreHidden() {
		User u = newUser("gone@example.com");
		jdbc.update("update users set is_deleted = true where id = ?", u.getId());
		assertThat(users.findByEmail("gone@example.com")).isEmpty();
		assertThat(users.count()).isZero();
	}

	@Test
	void refreshTokenHashIsUnique() {
		User u = newUser("rt@example.com");
		tokens.saveAndFlush(token(u, "a".repeat(64)));
		assertThatThrownBy(() -> tokens.saveAndFlush(token(u, "a".repeat(64))))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private RefreshToken token(User u, String hash) {
		RefreshToken t = new RefreshToken();
		t.setUser(u);
		t.setTokenHash(hash);
		t.setExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS));
		t.setFamilyId(UUID.randomUUID());
		return t;
	}

	@Test
	void revokeFamilyOnlyTouchesThatFamily() {
		User u = newUser("fam@example.com");
		RefreshToken a = token(u, "1".repeat(64));
		RefreshToken b = token(u, "2".repeat(64));
		a.setFamilyId(b.getFamilyId());
		tokens.saveAll(java.util.List.of(a, b, token(u, "3".repeat(64))));
		assertThat(tokens.revokeFamily(a.getFamilyId(), Instant.now())).isEqualTo(2);
		assertThat(tokens.findByTokenHash("3".repeat(64))).get().extracting(RefreshToken::isRevoked)
				.isEqualTo(false);
	}

	@Test
	void userLogIsPagedNewestFirstAsDto() throws Exception {
		User u = newUser("log@example.com");
		for (UserAction action : new UserAction[] { UserAction.LOGIN, UserAction.LOGOUT,
				UserAction.PASSWORD_CHANGED }) {
			UserLog l = new UserLog();
			l.setUser(u);
			l.setAction(action);
			l.setSubAction(UserSubAction.SUCCESS);
			logs.saveAndFlush(l);
			Thread.sleep(5);
		}
		var page = logs.findResponsesByUserId(u.getId(), PageRequest.of(0, 2));
		assertThat(page.getTotalElements()).isEqualTo(3);
		assertThat(page.getContent()).extracting(UserLogResponse::action)
				.containsExactly(UserAction.PASSWORD_CHANGED, UserAction.LOGOUT);
		assertThat(page.getContent().get(0).userName()).isEqualTo("Jane Doe");
	}

	@Test
	void userLogRequiresUser() {
		UserLog l = new UserLog();
		l.setAction(UserAction.LOGIN);
		l.setSubAction(UserSubAction.FAILURE);
		assertThatThrownBy(() -> logs.saveAndFlush(l)).isInstanceOf(Exception.class);
	}

	@Test
	void responsesExposeNoInternalFields() throws Exception {
		User u = newUser("ser@example.com");
		String userJson = json.writeValueAsString(UserResponse.from(u));
		assertThat(userJson).doesNotContain("active", "deleted", "passwordHash", "password", "token");

		UserLog l = new UserLog();
		l.setUser(u);
		l.setAction(UserAction.LOGIN);
		l.setSubAction(UserSubAction.SUCCESS);
		logs.saveAndFlush(l);
		String logJson = json.writeValueAsString(logs.findResponsesByUserId(u.getId(), PageRequest.of(0, 5))
				.getContent().get(0));
		assertThat(logJson).doesNotContain("active", "deleted", "passwordHash", "password", "token");
		assertThat(logJson).contains("createdOn", "userName");
	}

}
