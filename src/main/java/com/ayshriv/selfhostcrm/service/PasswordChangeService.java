package com.ayshriv.selfhostcrm.service;

import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.domain.UserAction;
import com.ayshriv.selfhostcrm.domain.UserSubAction;
import com.ayshriv.selfhostcrm.exception.ResourceNotFoundException;
import com.ayshriv.selfhostcrm.exception.ServiceException;
import com.ayshriv.selfhostcrm.repository.RefreshTokenRepository;
import com.ayshriv.selfhostcrm.repository.UserRepository;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Password change for both the web UI and the API: needs the current password, revokes refresh tokens. */
@Service
public class PasswordChangeService {

	private final UserRepository users;

	private final RefreshTokenRepository refreshTokens;

	private final UserLogService userLog;

	private final PasswordEncoder passwordEncoder;

	public PasswordChangeService(UserRepository users, RefreshTokenRepository refreshTokens,
			UserLogService userLog, PasswordEncoder passwordEncoder) {
		this.users = users;
		this.refreshTokens = refreshTokens;
		this.userLog = userLog;
		this.passwordEncoder = passwordEncoder;
	}

	/** {@code noRollbackFor} keeps the FAILURE audit row when the current password is wrong. */
	@Transactional(noRollbackFor = ServiceException.class)
	public void changePassword(Long userId, String currentPassword, String newPassword) {
		User user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException(ErrorMessages.E001));
		if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			userLog.record(user, UserAction.PASSWORD_CHANGED, UserSubAction.FAILURE);
			throw new ServiceException(ErrorMessages.E012);
		}
		PasswordPolicy.check(user.getEmail(), newPassword);
		Instant now = Instant.now();
		user.setPasswordHash(passwordEncoder.encode(newPassword));
		user.setCredentialsChangedAt(now);
		refreshTokens.revokeAllForUser(user.getId(), now);
		userLog.record(user, UserAction.PASSWORD_CHANGED, UserSubAction.SUCCESS);
	}

}
