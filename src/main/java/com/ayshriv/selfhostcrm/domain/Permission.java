package com.ayshriv.selfhostcrm.domain;

/**
 * Fine-grained permissions. Each one is exposed as a Spring authority with exactly this name, and
 * service methods are protected with {@code @PreAuthorize("hasAuthority('USER_VIEW')")}. Which role holds
 * which permission lives only in {@link RolePermissions}.
 */
public enum Permission {

	USER_VIEW, USER_UPDATE, USER_INVITE, USER_ROLE_CHANGE, USER_DELETE, INVITATION_MANAGE, AUDIT_VIEW_ALL

}
