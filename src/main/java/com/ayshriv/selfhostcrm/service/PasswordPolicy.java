package com.ayshriv.selfhostcrm.service;

import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import com.ayshriv.selfhostcrm.exception.ServiceException;

/** Password rules: 12 to 128 characters (also enforced by Bean Validation on the DTOs) and not the email. */
public final class PasswordPolicy {

	public static final int MIN_LENGTH = 12;

	public static final int MAX_LENGTH = 128;

	private PasswordPolicy() {
	}

	public static void check(String email, String password) {
		if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
			throw new ServiceException(ErrorMessages.E005);
		}
		if (email != null && password.trim().equalsIgnoreCase(email.trim())) {
			throw new ServiceException(ErrorMessages.E011);
		}
	}

}
