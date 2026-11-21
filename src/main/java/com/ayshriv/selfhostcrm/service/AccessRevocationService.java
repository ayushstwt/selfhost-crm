package com.ayshriv.selfhostcrm.service;

import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.repository.RefreshTokenRepository;
import com.ayshriv.selfhostcrm.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cuts off a user's existing access after a role, enabled or deleted change: bumps
 * {@code access_changed_at} (older access tokens and web sessions are rejected) and revokes every
 * refresh token. Must run inside the caller's transaction.
 */
@Service
public class AccessRevocationService {

	private final RefreshTokenRepository refreshTokens;

	private final UserRepository users;

	public AccessRevocationService(RefreshTokenRepository refreshTokens, UserRepository users) {
		this.users = users;
		this.refreshTokens = refreshTokens;
	}

	/**
	 * Apply all other changes to {@code user} first: the bulk update below flushes and then clears the
	 * persistence context, so later changes to the same instance would be lost.
	 */
	@Transactional
	public void revokeAccess(User user) {
		Instant now = Instant.now();
		// Next whole second: JWT iat has second precision, so every token minted up to and including the
		// current second is older than this and gets rejected (fail closed; a re-login within the same
		// second is rejected too and simply has to be repeated).
		user.setAccessChangedAt(now.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1));
		refreshTokens.revokeAllForUser(user.getId(), now);
	}

	/**
	 * Same cut-off for several users in two bulk updates (used when a team is deleted). Like the single-user
	 * variant it flushes and clears the persistence context, so call it last.
	 */
	@Transactional
	public void revokeAccess(Collection<Long> userIds) {
		if (userIds.isEmpty()) {
			return;
		}
		Instant now = Instant.now();
		users.bumpAccessChangedAt(userIds, now.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1), now);
		refreshTokens.revokeAllForUsers(userIds, now);
	}

}
