package com.ayshriv.selfhostcrm.domain;

/**
 * Audited user actions. Never extend with anything that carries secrets. The database has a CHECK
 * constraint (ck_user_log_action) listing these values: a new value needs a migration.
 */
public enum UserAction {

	LOGIN, LOGOUT, PASSWORD_CHANGED, REFRESH_REUSE_DETECTED, SETUP_COMPLETED,
	USER_UPDATED, USER_ROLE_CHANGED, USER_DISABLED, USER_ENABLED, USER_DELETED,
	INVITATION_CREATED, INVITATION_REVOKED, INVITATION_ACCEPTED

}
