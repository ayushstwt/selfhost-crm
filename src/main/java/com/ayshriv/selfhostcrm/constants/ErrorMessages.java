package com.ayshriv.selfhostcrm.constants;

import org.springframework.http.HttpStatus;

/**
 * Single source of truth for error code (the constant name), HTTP status and message template.
 * Add a constant here, throw the fitting exception with it, and the handlers do the rest.
 */
public enum ErrorMessages {

	E001(HttpStatus.NOT_FOUND, "Resource not found"),
	E002(HttpStatus.CONFLICT, "An account with this email already exists"),
	E003(HttpStatus.UNAUTHORIZED, "Authentication required"),
	E004(HttpStatus.FORBIDDEN, "Access denied"),
	E005(HttpStatus.BAD_REQUEST, "Validation failed"),
	E006(HttpStatus.UNAUTHORIZED, "Invalid email or password"),
	E007(HttpStatus.FORBIDDEN, "Account disabled"),
	E008(HttpStatus.NOT_FOUND, "Resource not found"),
	/** Reserved (for example for rate limiting). Login never returns it: a locked account looks like a wrong password. */
	E009(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Try again later"),
	E010(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token"),
	E011(HttpStatus.BAD_REQUEST, "Password must not be the same as the email"),
	E012(HttpStatus.BAD_REQUEST, "Current password is incorrect"),
	E013(HttpStatus.FORBIDDEN, "You are not allowed to perform this action on this user"),
	E014(HttpStatus.CONFLICT, "The last active administrator cannot be demoted, disabled or deleted"),
	E015(HttpStatus.FORBIDDEN, "You cannot change the role, status or existence of your own account"),
	/** One generic code for an invalid, expired, used or revoked invitation: nothing says which. */
	E016(HttpStatus.BAD_REQUEST, "This invitation link is invalid or has expired"),
	E017(HttpStatus.CONFLICT, "A pending invitation for this email was created by someone else"),
	E018(HttpStatus.FORBIDDEN, "You are not allowed to perform this action on this team"),
	E019(HttpStatus.NOT_FOUND, "Team not found"),
	E020(HttpStatus.CONFLICT, "A team with this name already exists"),
	E021(HttpStatus.NOT_FOUND, "This user is not a member of the team"),
	E022(HttpStatus.CONFLICT, "A disabled user cannot be added to a team"),
	E400(HttpStatus.BAD_REQUEST, "Bad request"),
	E405(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
	E415(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
	E500(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");

	/*
	 * Meaning of the codes: E001 resource not found, E002 duplicate email, E003 authentication
	 * required, E004 access denied, E005 validation failed, E006 invalid credentials, E007 account
	 * disabled, E008 setup already completed (404 on purpose: /setup must look absent), E009 too many
	 * attempts, E010 invalid or expired refresh token, E011 password equals email, E012 wrong current password, E013 target-based
	 * denial (wrong scope for this user), E014 last active administrator, E015 self-action not allowed, E016 invalid
	 * invitation, E017 pending invitation by someone else, E018 not allowed on this team, E019 team not found, E020 team name in use, E021 not a team member, E022 disabled user cannot join a team, E400/E405/E415/E500 generic HTTP errors.
	 */

	private final HttpStatus httpStatus;

	private final String message;

	ErrorMessages(HttpStatus httpStatus, String message) {
		this.httpStatus = httpStatus;
		this.message = message;
	}

	public HttpStatus getHttpStatus() {
		return httpStatus;
	}

	public String getMessage() {
		return message;
	}

	public String format(Object... args) {
		return String.format(message, args);
	}

}
