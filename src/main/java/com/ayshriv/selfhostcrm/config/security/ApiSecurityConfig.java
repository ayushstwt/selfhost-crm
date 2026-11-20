package com.ayshriv.selfhostcrm.config.security;

import com.ayshriv.selfhostcrm.domain.Permission;
import com.ayshriv.selfhostcrm.domain.Role;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

/**
 * API chain ({@code /api/**}): stateless, JWT bearer tokens via Spring Security's OAuth2 resource
 * server (Nimbus). CSRF is disabled for this chain only; the web chain keeps it on.
 */
@Configuration
@EnableMethodSecurity
public class ApiSecurityConfig {

	/** The only public API endpoints (all POST). Their credentials are in the body, not in a bearer header. */
	private static final Set<String> PUBLIC_PATHS = Set.of("/api/auth/login", "/api/auth/refresh",
			"/api/auth/logout", "/api/invitations/accept");

	@Bean
	@Order(1)
	SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
			ApiJwtAuthenticationConverter converter, ApiSecurityErrorHandler errorHandler) {
		DefaultBearerTokenResolver defaultResolver = new DefaultBearerTokenResolver();
		// A stale or expired access token must not break the public endpoints (for example logout).
		BearerTokenResolver resolver = request -> HttpMethod.POST.matches(request.getMethod())
				&& PUBLIC_PATHS.contains(request.getRequestURI()) ? null : defaultResolver.resolve(request);

		http.securityMatcher("/api/**")
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.requestCache(cache -> cache.disable())
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.POST, PUBLIC_PATHS.toArray(String[]::new)).permitAll()
						// Any signed-in user: their own account.
						.requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
						.requestMatchers(HttpMethod.POST, "/api/auth/change-password").authenticated()
						.requestMatchers(HttpMethod.GET, "/api/users/me/activity").authenticated()
						// Coarse permission per endpoint; the services add @PreAuthorize and the target rules.
						.requestMatchers(HttpMethod.GET, "/api/users", "/api/users/*").hasAuthority(Permission.USER_VIEW.name())
						.requestMatchers(HttpMethod.PATCH, "/api/users/*").hasAuthority(Permission.USER_UPDATE.name())
						.requestMatchers(HttpMethod.PATCH, "/api/users/*/role").hasAuthority(Permission.USER_ROLE_CHANGE.name())
						.requestMatchers(HttpMethod.DELETE, "/api/users/*").hasAuthority(Permission.USER_DELETE.name())
						.requestMatchers(HttpMethod.POST, "/api/invitations").hasAuthority(Permission.USER_INVITE.name())
						.requestMatchers(HttpMethod.GET, "/api/invitations").hasAuthority(Permission.INVITATION_MANAGE.name())
						.requestMatchers(HttpMethod.DELETE, "/api/invitations/*").hasAuthority(Permission.INVITATION_MANAGE.name())
						.requestMatchers(HttpMethod.GET, "/api/audit").hasAuthority(Permission.AUDIT_VIEW_ALL.name())
						// Deny by default: an endpoint without a rule above is reachable by ADMIN only.
						.anyRequest().hasRole(Role.ADMIN.name()))
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint(errorHandler)
						.accessDeniedHandler(errorHandler))
				.oauth2ResourceServer(oauth -> oauth
						.bearerTokenResolver(resolver)
						.authenticationEntryPoint(errorHandler)
						.accessDeniedHandler(errorHandler)
						.jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(converter)));
		return http.build();
	}

}
