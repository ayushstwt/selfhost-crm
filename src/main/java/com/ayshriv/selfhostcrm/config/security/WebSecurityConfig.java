package com.ayshriv.selfhostcrm.config.security;

import com.ayshriv.selfhostcrm.service.LoginAttemptService;
import com.ayshriv.selfhostcrm.service.SetupService;
import com.ayshriv.selfhostcrm.service.UserLogService;
import com.ayshriv.selfhostcrm.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.authentication.logout.LogoutHandler;

/**
 * Web (Thymeleaf) chain: session login, CSRF on, session fixation protection, logout.
 * Cookie flags (HttpOnly, SameSite=Lax, Secure via SESSION_COOKIE_SECURE) are set in application.yaml.
 * Lowest priority: {@link ApiSecurityConfig} claims {@code /api/**} first.
 */
@Configuration
public class WebSecurityConfig {

	@Bean
	@Order(Ordered.LOWEST_PRECEDENCE)
	SecurityFilterChain webSecurityFilterChain(HttpSecurity http, SetupService setupService,
			UserService userService, LoginAttemptService loginAttempts, UserLogService userLog,
			DaoAuthenticationProvider provider) {

		SavedRequestAwareAuthenticationSuccessHandler success = new SavedRequestAwareAuthenticationSuccessHandler() {
			@Override
			public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
					Authentication authentication) throws java.io.IOException, jakarta.servlet.ServletException {
				if (authentication.getPrincipal() instanceof UserPrincipal principal) {
					loginAttempts.recordSuccess(principal.getId());
					request.getSession().setAttribute(ActiveUserSessionFilter.AUTHENTICATED_AT,
							java.time.Instant.now());
				}
				super.onAuthenticationSuccess(request, response, authentication);
			}
		};
		success.setDefaultTargetUrl("/");

		AuthenticationFailureHandler failure = (request, response, exception) -> {
			loginAttempts.recordFailure(request.getParameter("username"), exception);
			// Wrong password, unknown email and locked accounts all look the same (no enumeration).
			response.sendRedirect(request.getContextPath()
					+ (exception instanceof DisabledException ? "/login?disabled" : "/login?error"));
		};

		LogoutHandler auditLogout = (request, response, authentication) -> {
			if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
				userLog.recordForUserId(principal.getId(), com.ayshriv.selfhostcrm.domain.UserAction.LOGOUT, com.ayshriv.selfhostcrm.domain.UserSubAction.SUCCESS);
			}
		};

		http.authenticationProvider(provider)
				.addFilterBefore(new SetupRedirectFilter(setupService), AuthorizationFilter.class)
				.addFilterAfter(new ActiveUserSessionFilter(userService), SecurityContextHolderFilter.class)
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
						.requestMatchers("/actuator/**").denyAll()
						.requestMatchers("/setup", "/login", "/error").permitAll()
						.requestMatchers("/css/**", "/js/**", "/images/**", "/webjars/**", "/favicon.ico")
						.permitAll()
						.anyRequest().authenticated())
				.formLogin(form -> form
						.loginPage("/login")
						.loginProcessingUrl("/login")
						.successHandler(success)
						.failureHandler(failure)
						.permitAll())
				.logout(logout -> logout
						.logoutUrl("/logout")
						.addLogoutHandler(auditLogout)
						.logoutSuccessUrl("/login?logout")
						.invalidateHttpSession(true)
						.deleteCookies("JSESSIONID")
						.permitAll())
				.sessionManagement(session -> session.sessionFixation(fix -> fix.changeSessionId()));
		return http.build();
	}


}
