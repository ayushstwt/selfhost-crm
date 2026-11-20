package com.ayshriv.selfhostcrm.config.security;

import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import java.io.Serializable;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Authenticated user as seen by Spring Security. Exposes the user id for later features (teams). */
public final class UserPrincipal implements UserDetails, Serializable {

	private static final long serialVersionUID = 1L;

	private final Long id;

	private final String email;

	private final String displayName;

	private final String passwordHash;

	private final Role role;

	private final boolean enabled;

	private final Instant lockedUntil;

	public UserPrincipal(User user) {
		this.id = user.getId();
		this.email = user.getEmail();
		this.displayName = user.getDisplayName();
		this.passwordHash = user.getPasswordHash();
		this.role = user.getRole();
		this.enabled = user.isEnabled();
		this.lockedUntil = user.getLockedUntil();
	}

	public Long getId() {
		return id;
	}

	public String getDisplayName() {
		return displayName;
	}

	public Role getRole() {
		return role;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	@Override
	public String getUsername() {
		return email;
	}

	@Override
	public boolean isAccountNonLocked() {
		return lockedUntil == null || !lockedUntil.isAfter(Instant.now());
	}

	@Override
	public boolean isEnabled() {
		return enabled;
	}

}
