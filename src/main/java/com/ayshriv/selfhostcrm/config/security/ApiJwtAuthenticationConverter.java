package com.ayshriv.selfhostcrm.config.security;

import com.ayshriv.selfhostcrm.service.UserService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Turns a verified JWT into an authentication. The user is looked up on every request, so a token of
 * a disabled or deleted user is rejected immediately, and authorities come from the database role.
 */
@Component
public class ApiJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	private final UserService userService;

	public ApiJwtAuthenticationConverter(UserService userService) {
		this.userService = userService;
	}

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		Long userId;
		try {
			userId = Long.valueOf(jwt.getSubject());
		}
		catch (NumberFormatException ex) {
			throw new BadCredentialsException("Invalid token subject");
		}
		UserPrincipal principal = userService.findPrincipal(userId)
				.filter(UserPrincipal::isEnabled)
				.orElseThrow(() -> new BadCredentialsException("Unknown or disabled user"));
		return new JwtAuthenticationToken(jwt, principal.getAuthorities(), String.valueOf(principal.getId()));
	}

}
