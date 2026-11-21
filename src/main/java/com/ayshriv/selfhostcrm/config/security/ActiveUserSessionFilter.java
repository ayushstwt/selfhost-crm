package com.ayshriv.selfhostcrm.config.security;

import com.ayshriv.selfhostcrm.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps live web sessions honest, with one database lookup per request:
 * <ul>
 * <li>the session ends when its user was disabled or deleted, when the credentials changed, or when
 * role/enabled/deleted state changed ({@code access_changed_at}) after the session authenticated;</li>
 * <li>otherwise the authentication is rebuilt from the database principal, so roles and permissions are
 * never stale (a role edited directly in the database takes effect on the next request).</li>
 * </ul>
 * The session records its authentication time under {@link #AUTHENTICATED_AT}; the session that performs a
 * password change refreshes it so it stays logged in.
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
		if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
			HttpSession session = request.getSession(false);
			Optional<UserPrincipal> fresh = userService.findPrincipal(principal.getId())
					.filter(p -> stillValid(session, p));
			if (fresh.isPresent()) {
				SecurityContext context = SecurityContextHolder.createEmptyContext();
				context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(fresh.get(), null,
						fresh.get().getAuthorities()));
				SecurityContextHolder.setContext(context);
			}
			else {
				SecurityContextHolder.clearContext();
				if (session != null) {
					session.invalidate();
				}
			}
		}
		chain.doFilter(request, response);
	}

	private static boolean stillValid(HttpSession session, UserPrincipal current) {
		if (!current.isEnabled()) {
			return false;
		}
		Object attr = session == null ? null : session.getAttribute(AUTHENTICATED_AT);
		Instant authenticatedAt = attr instanceof Instant i ? i
				: session == null ? Instant.EPOCH : Instant.ofEpochMilli(session.getCreationTime());
		Instant credentialsChanged = current.getCredentialsChangedAt();
		if (credentialsChanged != null && authenticatedAt.isBefore(credentialsChanged)) {
			return false;
		}
		return !authenticatedAt.isBefore(current.getAccessChangedAt());
	}

}
