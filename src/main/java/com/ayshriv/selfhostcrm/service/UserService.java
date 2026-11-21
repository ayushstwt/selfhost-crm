package com.ayshriv.selfhostcrm.service;

import com.ayshriv.selfhostcrm.config.security.UserPrincipal;
import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.exception.ResourceNotFoundException;
import com.ayshriv.selfhostcrm.repository.TeamMemberRepository;
import com.ayshriv.selfhostcrm.repository.UserRepository;
import com.ayshriv.selfhostcrm.web.UserResponse;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

	private final UserRepository users;

	private final TeamMemberRepository teamMembers;

	public UserService(UserRepository users, TeamMemberRepository teamMembers) {
		this.users = users;
		this.teamMembers = teamMembers;
	}

	@Transactional(readOnly = true)
	public UserResponse getById(Long id) {
		return users.findById(id).map(UserResponse::from)
				.orElseThrow(() -> new ResourceNotFoundException(ErrorMessages.E001));
	}

	/**
	 * The per-request lookup for both security chains: the user row plus one query for the user's team
	 * memberships (two queries in total, whatever the number of teams).
	 */
	@Transactional(readOnly = true)
	public Optional<UserPrincipal> findPrincipal(Long id) {
		return users.findById(id).map(this::toPrincipal);
	}

	/** Builds the principal for an already loaded user (adds the one membership query). */
	public UserPrincipal toPrincipal(User user) {
		return new UserPrincipal(user, teamMembers.findMemberships(user.getId()));
	}

}
