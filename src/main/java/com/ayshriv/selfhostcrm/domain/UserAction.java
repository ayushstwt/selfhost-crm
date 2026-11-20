package com.ayshriv.selfhostcrm.domain;

/** Audited user actions. Never extend with anything that carries secrets. */
public enum UserAction {

	LOGIN, LOGOUT, PASSWORD_CHANGED, REFRESH_REUSE_DETECTED, SETUP_COMPLETED

}
