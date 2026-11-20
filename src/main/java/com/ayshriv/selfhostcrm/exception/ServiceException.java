package com.ayshriv.selfhostcrm.exception;

import com.ayshriv.selfhostcrm.constants.ErrorMessages;

public class ServiceException extends BaseException {

	public ServiceException(ErrorMessages errorMessage, Object... args) {
		super(errorMessage, args);
	}

}
