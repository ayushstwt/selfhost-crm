package com.ayshriv.selfhostcrm.exception;

import com.ayshriv.selfhostcrm.constants.ErrorMessages;

public class AppException extends BaseException {

	public AppException(ErrorMessages errorMessage, Object... args) {
		super(errorMessage, args);
	}

}
