package com.ayshriv.selfhostcrm.config.security;

import com.ayshriv.selfhostcrm.repository.UserRepository;
import com.ayshriv.selfhostcrm.service.UserService;
import java.util.Locale;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppUserDetailsService implements UserDetailsService {

	private final UserRepository users;

	private final UserService userService;

	public AppUserDetailsService(UserRepository users, UserService userService) {
		this.users = users;
		this.userService = userService;
	}

	@Override
	@Transactional(readOnly = true)
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		String email = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
		return users.findByEmail(email).map(userService::toPrincipal)
				.orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
	}

}
