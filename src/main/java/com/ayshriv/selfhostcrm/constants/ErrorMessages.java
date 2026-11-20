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
	E400(HttpStatus.BAD_REQUEST, "Bad request"),
	E405(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
	E415(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
	E500(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");

	/*
	 * Meaning of the codes: E001 resource not found, E002 duplicate email, E003 authentication
	 * required, E004 access denied, E005 validation failed, E006 invalid credentials, E007 account
	 * disabled, E008 setup already completed (404 on purpose: /setup must look absent), E009 too many
	 * attempts, E010 invalid or expired refresh token, E011 password equals email, E012 wrong current password, E400/E405/E415/E500 generic HTTP errors.
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
