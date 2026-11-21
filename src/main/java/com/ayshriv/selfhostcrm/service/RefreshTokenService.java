package com.ayshriv.selfhostcrm.service;

import com.ayshriv.selfhostcrm.config.security.JwtProperties;
import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import com.ayshriv.selfhostcrm.domain.RefreshToken;
import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.domain.UserAction;
import com.ayshriv.selfhostcrm.domain.UserSubAction;
import com.ayshriv.selfhostcrm.exception.BaseException;
import com.ayshriv.selfhostcrm.exception.UnauthorizedException;
import com.ayshriv.selfhostcrm.repository.RefreshTokenRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque refresh tokens: 256 random bits, URL-safe. Only the SHA-256 hash is stored; the raw value is
 * handed out once and never logged. Rotation keeps a token family; reusing a revoked token kills it.
 */
@Service
public class RefreshTokenService {

	private final RefreshTokenRepository tokens;

	private final UserLogService userLog;

	private final JwtProperties properties;

	public RefreshTokenService(RefreshTokenRepository tokens, UserLogService userLog, JwtProperties properties) {
		this.tokens = tokens;
		this.userLog = userLog;
		this.properties = properties;
	}

	/** Result of issuing or rotating: the raw token plus what is needed to mint the access token. */
	public record IssuedToken(String rawToken, Long userId, String email, Role role) {
	}

	@Transactional
	public IssuedToken issue(User user) {
		return create(user, UUID.randomUUID(), null).issued();
	}

	/**
	 * Exchanges a refresh token for a new one. {@code noRollbackFor} keeps the family revocation and the
	 * audit row when a reuse is detected and the call ends with an exception.
	 */
	@Transactional(noRollbackFor = BaseException.class)
	public IssuedToken rotate(String rawToken) {
		Instant now = Instant.now();
		RefreshToken old = tokens.findForUpdateByTokenHash(hash(rawToken))
				.orElseThrow(() -> new UnauthorizedException(ErrorMessages.E010));
		User user = old.getUser();

		if (old.isRevoked()) {
			tokens.revokeFamily(old.getFamilyId(), now);
			userLog.record(user, UserAction.REFRESH_REUSE_DETECTED, UserSubAction.FAILURE);
			throw new UnauthorizedException(ErrorMessages.E010);
		}
		if (old.isExpired(now)) {
			throw new UnauthorizedException(ErrorMessages.E010);
		}
		if (!user.isEnabled()) {
			tokens.revokeFamily(old.getFamilyId(), now);
			throw new UnauthorizedException(ErrorMessages.E010);
		}

		Created created = create(user, old.getFamilyId(), old);
		old.setRevokedAt(now);
		old.setReplacedById(created.entity().getId());
		return created.issued();
	}

	/**
	 * Logout: the token itself is the credential. Idempotent and silent: an unknown, expired or already
	 * revoked token behaves exactly like a live one, so the caller learns nothing about it.
	 */
	@Transactional
	public void revoke(String rawToken) {
		tokens.findForUpdateByTokenHash(hash(rawToken)).filter(t -> !t.isRevoked())
				.ifPresent(t -> t.setRevokedAt(Instant.now()));
	}

	@Transactional
	public int deleteExpired(Instant cutoff) {
		tokens.clearReplacementsOfExpired(cutoff, Instant.now());
		return tokens.deleteExpired(cutoff);
	}

	private record Created(RefreshToken entity, IssuedToken issued) {
	}

	private Created create(User user, UUID familyId, RefreshToken previous) {
		String raw = TokenHasher.newToken();

		RefreshToken token = new RefreshToken();
		token.setUser(user);
		token.setTokenHash(hash(raw));
		token.setExpiresAt(Instant.now().plus(properties.refreshTokenTtl()));
		token.setFamilyId(familyId);
		token = tokens.saveAndFlush(token);
		return new Created(token, new IssuedToken(raw, user.getId(), user.getEmail(), user.getRole()));
	}

	static String hash(String raw) {
		return TokenHasher.hash(raw);
	}

}
