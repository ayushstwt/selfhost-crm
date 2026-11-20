package com.ayshriv.selfhostcrm.service;

import com.ayshriv.selfhostcrm.config.security.LockoutProperties;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.domain.UserAction;
import com.ayshriv.selfhostcrm.domain.UserSubAction;
import com.ayshriv.selfhostcrm.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.security.authentication.LockedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Login bookkeeping shared by the web and API logins: counters, temporary lockout and audit entries. */
@Service
public class LoginAttemptService {

	private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

	private final UserRepository users;

	private final UserLogService userLog;

	private final LockoutProperties lockout;

	public LoginAttemptService(UserRepository users, UserLogService userLog, LockoutProperties lockout) {
		this.users = users;
		this.userLog = userLog;
		this.lockout = lockout;
	}

	@Transactional
	public void recordSuccess(Long userId) {
		users.findById(userId).ifPresent(user -> {
			user.setFailedLoginCount(0);
			user.setLockedUntil(null);
			user.setLastLoginAt(Instant.now());
			userLog.record(user, UserAction.LOGIN, UserSubAction.SUCCESS);
		});
	}

	/**
	 * Records a failed login. Failures for emails that do not exist only go to the application log
	 * (there is no user to attach an audit row to). Only wrong passwords count towards the lockout.
	 */
	@Transactional
	public void recordFailure(String rawEmail, AuthenticationException cause) {
		String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
		User user = users.findForUpdateByEmail(email).orElse(null);
		if (user == null) {
			// Only a short fingerprint is logged: people sometimes type their password into the email field.
			log.warn("Failed login for unknown account (fingerprint {})", fingerprint(email));
			return;
		}
		if (cause instanceof LockedException) {
			log.warn("Login attempt rejected: account {} is locked until {}", user.getId(), user.getLockedUntil());
		}
		boolean currentlyLocked = user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now());
		if (cause instanceof BadCredentialsException && !currentlyLocked) {
			int count = user.getFailedLoginCount() + 1;
			if (count >= lockout.maxAttempts()) {
				user.setLockedUntil(Instant.now().plus(lockout.duration()));
				user.setFailedLoginCount(0);
				log.warn("Account {} locked until {} after repeated failed logins", user.getId(),
						user.getLockedUntil());
			}
			else {
				user.setFailedLoginCount(count);
			}
		}
		userLog.record(user, UserAction.LOGIN, UserSubAction.FAILURE);
	}

	/** First 8 hex characters of SHA-256: enough to correlate repeated attempts, useless to read back. */
	private static String fingerprint(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 4);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}

}
