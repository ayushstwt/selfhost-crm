package com.ayshriv.selfhostcrm.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ayshriv.selfhostcrm.TestcontainersConfiguration;
import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.repository.UserRepository;
import com.ayshriv.selfhostcrm.service.RefreshTokenService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@SpringBootTest
class RefreshTokenCleanupTest {

	@Autowired
	RefreshTokenService service;

	@Autowired
	UserRepository users;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void expiredTokensAreDeletedEvenWhenLiveOnesPointAtThem() {
		jdbc.execute("delete from user_log");
		jdbc.execute("delete from invitations");
		jdbc.execute("delete from refresh_tokens");
		jdbc.execute("delete from users");
		User u = new User();
		u.setEmail("cleanup@example.com");
		u.setDisplayName("Cleanup");
		u.setPasswordHash("x");
		u.setRole(Role.MEMBER);
		u = users.saveAndFlush(u);

		String first = service.issue(u).rawToken();
		service.rotate(first);
		// the rotated-away (revoked) token expires; its replacement stays live
		jdbc.update("update refresh_tokens set expires_at = now() - interval '1 hour' where revoked_at is not null");
		// worst case: the live token points at an expired one
		jdbc.update("update refresh_tokens set replaced_by_id = (select id from refresh_tokens "
				+ "where revoked_at is not null) where revoked_at is null");

		int removed = service.deleteExpired(Instant.now());

		assertThat(removed).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Long.class)).isEqualTo(1);
	}

}
