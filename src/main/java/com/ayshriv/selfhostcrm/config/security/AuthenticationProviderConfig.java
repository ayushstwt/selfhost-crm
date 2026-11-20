package com.ayshriv.selfhostcrm.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Shared credential check for the web form login and the API login.
 * The password is always checked first. A locked account is then refused even with the right password,
 * and callers turn that into the same generic failure as a wrong password. A disabled account is only
 * reported after the correct password, so the response never reveals that an email exists.
 */
@Configuration
public class AuthenticationProviderConfig {

	@Bean
	DaoAuthenticationProvider daoAuthenticationProvider(UserDetailsService userDetailsService,
			PasswordEncoder passwordEncoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
		provider.setPasswordEncoder(passwordEncoder);
		// No pre-checks: the password is always verified (same BCrypt cost for every outcome), so response
		// time does not reveal whether an account exists or is locked.
		provider.setPreAuthenticationChecks(user -> { });
		provider.setPostAuthenticationChecks(user -> {
			if (!user.isAccountNonLocked()) {
				throw new LockedException("Account temporarily locked");
			}
			if (!user.isEnabled()) {
				throw new DisabledException("Account disabled");
			}
		});
		return provider;
	}

}
