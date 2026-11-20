package com.ayshriv.selfhostcrm.config.security;

import com.ayshriv.selfhostcrm.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a live web session when its user was disabled or removed, or when the credentials changed after
 * the session was authenticated. The session records its authentication time under
 * {@link #AUTHENTICATED_AT}; the session that performs a password change refreshes it so it stays logged in.
 */
public class ActiveUserSessionFilter extends OncePerRequestFilter {

	/** Session attribute holding the {@link Instant} this session authenticated (or re-confirmed credentials). */
	public static final String AUTHENTICATED_AT = "authenticatedAt";

	private final UserService userService;

	public ActiveUserSessionFilter(UserService userService) {
		this.userService = userService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal
				&& !stillValid(request.getSession(false), principal)) {
			SecurityContextHolder.clearContext();
			HttpSession session = request.getSession(false);
			if (session != null) {
				session.invalidate();
			}
		}
		chain.doFilter(request, response);
	}

	private boolean stillValid(HttpSession session, UserPrincipal principal) {
		return userService.sessionStatus(principal.getId()).map(status -> {
			if (!status.enabled()) {
				return false;
			}
			Instant changed = status.credentialsChangedAt();
			if (changed == null) {
				return true;
			}
			Object attr = session == null ? null : session.getAttribute(AUTHENTICATED_AT);
			Instant authenticatedAt = attr instanceof Instant i ? i
					: session == null ? Instant.EPOCH : Instant.ofEpochMilli(session.getCreationTime());
			return !authenticatedAt.isBefore(changed);
		}).orElse(false);
	}

}
