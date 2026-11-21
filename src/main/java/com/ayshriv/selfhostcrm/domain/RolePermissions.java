package com.ayshriv.selfhostcrm.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The single place that maps a role to its permissions. The JWT carries only the role; permissions are
 * always derived here, server-side. MANAGER holds the permissions but is further limited per target by
 * {@code UserAccessPolicy} (MEMBER targets only, own invitations only).
 */
public final class RolePermissions {

	private static final Map<Role, Set<Permission>> PERMISSIONS = new EnumMap<>(Role.class);

	static {
		PERMISSIONS.put(Role.ADMIN, EnumSet.allOf(Permission.class));
		PERMISSIONS.put(Role.MANAGER, EnumSet.of(Permission.USER_VIEW, Permission.USER_UPDATE,
				Permission.USER_INVITE, Permission.INVITATION_MANAGE, Permission.TEAM_MEMBERS_MANAGE));
		PERMISSIONS.put(Role.MEMBER, EnumSet.noneOf(Permission.class));
	}

	private RolePermissions() {
	}

	public static Set<Permission> of(Role role) {
		return Set.copyOf(PERMISSIONS.get(role));
	}

	public static boolean has(Role role, Permission permission) {
		return PERMISSIONS.get(role).contains(permission);
	}

}
