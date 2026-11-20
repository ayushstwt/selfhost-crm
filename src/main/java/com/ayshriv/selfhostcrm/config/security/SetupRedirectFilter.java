package com.ayshriv.selfhostcrm.config.security;

import com.ayshriv.selfhostcrm.service.SetupService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * While no user exists, every web route redirects to {@code /setup}. Not a Spring bean on purpose:
 * it is added inside the web security chain so it runs before authentication redirects.
 */
public class SetupRedirectFilter extends OncePerRequestFilter {

	private final SetupService setupService;

	public SetupRedirectFilter(SetupService setupService) {
		this.setupService = setupService;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return path.equals("/setup") || path.equals("/api") || path.startsWith("/api/")
				|| path.startsWith("/actuator/") || path.startsWith("/css/") || path.startsWith("/js/")
				|| path.startsWith("/images/") || path.startsWith("/webjars/") || path.equals("/favicon.ico")
				|| path.equals("/error");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (setupService.isSetupRequired()) {
			response.sendRedirect(request.getContextPath() + "/setup");
			return;
		}
		chain.doFilter(request, response);
	}

}
