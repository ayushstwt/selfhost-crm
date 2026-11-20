package com.ayshriv.selfhostcrm.exception;

import com.ayshriv.selfhostcrm.constants.ErrorMessages;

public class ForbiddenException extends BaseException {

	public ForbiddenException(ErrorMessages errorMessage, Object... args) {
		super(errorMessage, args);
	}

}
