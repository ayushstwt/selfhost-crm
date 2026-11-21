package com.ayshriv.selfhostcrm.service;

import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.repository.RefreshTokenRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

	public AccessRevocationService(RefreshTokenRepository refreshTokens) {
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

}
