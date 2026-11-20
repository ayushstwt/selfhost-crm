package com.ayshriv.selfhostcrm.exception;

import com.ayshriv.selfhostcrm.constants.ErrorMessages;

public class ResourceAlreadyExistException extends BaseException {

	public ResourceAlreadyExistException(ErrorMessages errorMessage, Object... args) {
		super(errorMessage, args);
	}

}
