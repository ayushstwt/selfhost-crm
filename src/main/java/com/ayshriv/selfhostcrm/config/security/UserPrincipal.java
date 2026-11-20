package com.ayshriv.selfhostcrm.config.security;

import com.ayshriv.selfhostcrm.domain.Permission;
import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.RolePermissions;
import com.ayshriv.selfhostcrm.domain.User;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Authenticated user as seen by Spring Security. Exposes the user id for later features (teams). */
public final class UserPrincipal implements UserDetails, Serializable {

	private static final long serialVersionUID = 2L;

	private final Long id;

	private final String email;

	private final String displayName;

	private final String passwordHash;

	private final Role role;

	private final boolean enabled;

	private final Instant lockedUntil;

	private final Instant credentialsChangedAt;

	private final Instant accessChangedAt;

	public UserPrincipal(User user) {
		this.credentialsChangedAt = user.getCredentialsChangedAt();
		this.accessChangedAt = user.getAccessChangedAt();
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

	public Instant getCredentialsChangedAt() {
		return credentialsChangedAt;
	}

	public Instant getAccessChangedAt() {
		return accessChangedAt;
	}

	/** {@code ROLE_<role>} plus one authority per permission of the role (derived, never taken from a token). */
	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		List<GrantedAuthority> authorities = new ArrayList<>();
		authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
		RolePermissions.of(role).stream().map(Permission::name).sorted()
				.forEach(name -> authorities.add(new SimpleGrantedAuthority(name)));
		return List.copyOf(authorities);
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
