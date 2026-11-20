package com.ayshriv.selfhostcrm.service;

import com.ayshriv.selfhostcrm.config.security.UserPrincipal;
import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.exception.ForbiddenException;
import com.ayshriv.selfhostcrm.exception.ResourceNotFoundException;
import com.ayshriv.selfhostcrm.exception.UnauthorizedException;
import com.ayshriv.selfhostcrm.repository.UserRepository;
import com.ayshriv.selfhostcrm.web.TokenResponse;
import java.util.Locale;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

/**
 * API authentication flows.
 *
 * <p><b>Must NOT be {@code @Transactional}.</b> On a failed login this class records the failure (counter,
 * lockout, audit row) in its own transaction and then throws. An outer transaction would roll all of that
 * back together with the exception, which would silently disable lockout and the audit trail.
 * {@code AuthServiceTransactionTest} fails if a transaction is ever added here.
 */
@Service
public class AuthService {

	private final DaoAuthenticationProvider provider;

	private final LoginAttemptService loginAttempts;

	private final RefreshTokenService refreshTokens;

	private final JwtService jwtService;

	private final UserRepository users;

	public AuthService(DaoAuthenticationProvider provider, LoginAttemptService loginAttempts,
			RefreshTokenService refreshTokens, JwtService jwtService, UserRepository users) {
		this.provider = provider;
		this.loginAttempts = loginAttempts;
		this.refreshTokens = refreshTokens;
		this.jwtService = jwtService;
		this.users = users;
	}

	public TokenResponse login(String email, String password) {
		String normalized = email.trim().toLowerCase(Locale.ROOT);
		Authentication authentication;
		try {
			authentication = provider.authenticate(
					UsernamePasswordAuthenticationToken.unauthenticated(normalized, password));
		}
		catch (AuthenticationException ex) {
			// Lockouts are logged server-side by LoginAttemptService only; the client sees the same
			// generic 401 as for a wrong password, so nothing reveals that the account exists or is locked.
			loginAttempts.recordFailure(normalized, ex);
			if (ex instanceof DisabledException) {
				// only reachable after the correct password was presented
				throw new ForbiddenException(ErrorMessages.E007);
			}
			throw new UnauthorizedException(ErrorMessages.E006);
		}
		UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
		loginAttempts.recordSuccess(principal.getId());
		User user = users.findById(principal.getId())
				.orElseThrow(() -> new UnauthorizedException(ErrorMessages.E006));
		return toResponse(refreshTokens.issue(user));
	}

	public TokenResponse refresh(String rawRefreshToken) {
		return toResponse(refreshTokens.rotate(rawRefreshToken));
	}

	public void logout(String rawRefreshToken) {
		refreshTokens.revoke(rawRefreshToken);
	}

	private TokenResponse toResponse(RefreshTokenService.IssuedToken issued) {
		JwtService.AccessToken access = jwtService.issueAccessToken(issued.userId(), issued.email(),
				issued.role());
		return new TokenResponse(access.value(), issued.rawToken(), "Bearer", access.expiresInSeconds());
	}

}
