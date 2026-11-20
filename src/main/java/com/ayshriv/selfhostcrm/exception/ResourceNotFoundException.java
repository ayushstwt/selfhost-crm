package com.ayshriv.selfhostcrm.exception;

import com.ayshriv.selfhostcrm.constants.ErrorMessages;

public class ResourceNotFoundException extends BaseException {

	public ResourceNotFoundException(ErrorMessages errorMessage, Object... args) {
		super(errorMessage, args);
	}

}
