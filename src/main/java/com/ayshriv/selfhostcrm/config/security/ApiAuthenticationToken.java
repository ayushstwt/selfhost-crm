package com.ayshriv.selfhostcrm.config.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Authentication of an API request: the principal is the {@link UserPrincipal} loaded for this request
 * (id, role, derived authorities), the credentials are the verified JWT.
 */
public final class ApiAuthenticationToken extends AbstractAuthenticationToken {

	private static final long serialVersionUID = 1L;

	private final UserPrincipal principal;

	private final transient Jwt jwt;

	public ApiAuthenticationToken(UserPrincipal principal, Jwt jwt) {
		super(principal.getAuthorities());
		this.principal = principal;
		this.jwt = jwt;
		setAuthenticated(true);
	}

	@Override
	public Object getCredentials() {
		return jwt;
	}

	@Override
	public UserPrincipal getPrincipal() {
		return principal;
	}

	@Override
	public String getName() {
		return String.valueOf(principal.getId());
	}

}
