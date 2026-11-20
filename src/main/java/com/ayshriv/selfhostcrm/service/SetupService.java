package com.ayshriv.selfhostcrm.service;

import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import com.ayshriv.selfhostcrm.web.UserResponse;
import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.domain.UserAction;
import com.ayshriv.selfhostcrm.domain.UserSubAction;
import com.ayshriv.selfhostcrm.exception.ResourceNotFoundException;
import com.ayshriv.selfhostcrm.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** First-run setup: creates the first Admin exactly once, even under concurrent requests. */
@Service
public class SetupService {

	private final UserRepository users;

	private final UserLogService userLog;

	private final PasswordEncoder passwordEncoder;

	private final JdbcTemplate jdbc;

	public SetupService(UserRepository users, UserLogService userLog, PasswordEncoder passwordEncoder,
			JdbcTemplate jdbc) {
		this.users = users;
		this.userLog = userLog;
		this.passwordEncoder = passwordEncoder;
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public boolean isSetupRequired() {
		return !users.existsAnyUser();
	}

	/** Setup is permanently closed (404) as soon as any user exists. */
	@Transactional(readOnly = true)
	public void assertSetupAllowed() {
		if (users.existsAnyUser()) {
			throw new ResourceNotFoundException(ErrorMessages.E008);
		}
	}

	@Transactional
	public UserResponse createFirstAdmin(String displayName, String email, String rawPassword) {
		// Serializes concurrent setups: later transactions wait here, then see the committed admin.
		jdbc.execute("lock table users in share row exclusive mode");
		assertSetupAllowed();
		PasswordPolicy.check(email, rawPassword);

		User admin = new User();
		admin.setEmail(email);
		admin.setDisplayName(displayName.trim());
		admin.setPasswordHash(passwordEncoder.encode(rawPassword));
		admin.setRole(Role.ADMIN);
		admin = users.saveAndFlush(admin);
		userLog.record(admin, UserAction.SETUP_COMPLETED, UserSubAction.SUCCESS);
		return UserResponse.from(admin);
	}

}
