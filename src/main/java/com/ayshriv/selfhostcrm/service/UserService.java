package com.ayshriv.selfhostcrm.service;

import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import com.ayshriv.selfhostcrm.web.UserResponse;
import com.ayshriv.selfhostcrm.exception.ResourceNotFoundException;
import com.ayshriv.selfhostcrm.repository.UserRepository;
import com.ayshriv.selfhostcrm.config.security.UserPrincipal;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

	private final UserRepository users;

	public UserService(UserRepository users) {
		this.users = users;
	}

	@Transactional(readOnly = true)
	public UserResponse getById(Long id) {
		return users.findById(id).map(UserResponse::from)
				.orElseThrow(() -> new ResourceNotFoundException(ErrorMessages.E001));
	}

	@Transactional(readOnly = true)
	public Optional<UserPrincipal> findPrincipal(Long id) {
		return users.findById(id).map(UserPrincipal::new);
	}

	/** What a live web session needs to know about its user. */
	public record SessionStatus(boolean enabled, Instant credentialsChangedAt) {
	}

	/** Empty when the user no longer exists (deleted). Used to cut off live web sessions. */
	@Transactional(readOnly = true)
	public Optional<SessionStatus> sessionStatus(Long id) {
		return users.findById(id).map(u -> new SessionStatus(u.isEnabled(), u.getCredentialsChangedAt()));
	}

}
