package com.ayshriv.selfhostcrm.config.security;

import com.ayshriv.selfhostcrm.domain.Permission;
import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.RolePermissions;
import com.ayshriv.selfhostcrm.domain.TeamRole;
import com.ayshriv.selfhostcrm.domain.User;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Authenticated user as seen by Spring Security. Exposes the user id and the user's team memberships. */
public final class UserPrincipal implements UserDetails, Serializable {

	private static final long serialVersionUID = 3L;

	private final Long id;

	private final String email;

	private final String displayName;

	private final String passwordHash;

	private final Role role;

	private final boolean enabled;

	private final Instant lockedUntil;

	private final Instant credentialsChangedAt;

	private final Instant accessChangedAt;

	private final List<TeamMembership> memberships;

	public UserPrincipal(User user) {
		this(user, List.of());
	}

	/** {@code memberships} are the user's live team memberships, loaded once with the user row. */
	public UserPrincipal(User user, List<TeamMembership> memberships) {
		this.memberships = List.copyOf(memberships);
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

	/** Teams this user belongs to (no database access: read from the principal). */
	public List<TeamMembership> getMemberships() {
		return memberships;
	}

	public Set<Long> teamIds() {
		return memberships.stream().map(TeamMembership::teamId).collect(Collectors.toUnmodifiableSet());
	}

	public boolean isMemberOf(Long teamId) {
		return memberships.stream().anyMatch(m -> m.teamId().equals(teamId));
	}

	public boolean isLeadOf(Long teamId) {
		return memberships.stream().anyMatch(m -> m.teamId().equals(teamId) && m.teamRole() == TeamRole.LEAD);
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
