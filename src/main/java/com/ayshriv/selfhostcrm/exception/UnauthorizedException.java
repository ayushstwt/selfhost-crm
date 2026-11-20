package com.ayshriv.selfhostcrm.exception;

import com.ayshriv.selfhostcrm.constants.ErrorMessages;

public class UnauthorizedException extends BaseException {

	public UnauthorizedException(ErrorMessages errorMessage, Object... args) {
		super(errorMessage, args);
	}

}
