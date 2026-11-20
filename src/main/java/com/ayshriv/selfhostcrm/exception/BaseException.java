package com.ayshriv.selfhostcrm.exception;

import com.ayshriv.selfhostcrm.constants.ErrorMessages;

/** Base of all application exceptions; the status and code come from the {@link ErrorMessages} constant. */
public abstract class BaseException extends RuntimeException {

	private final ErrorMessages errorMessage;

	protected BaseException(ErrorMessages errorMessage, Object... args) {
		super(args == null || args.length == 0 ? errorMessage.getMessage() : errorMessage.format(args));
		this.errorMessage = errorMessage;
	}

	public ErrorMessages getErrorMessage() {
		return errorMessage;
	}

}
