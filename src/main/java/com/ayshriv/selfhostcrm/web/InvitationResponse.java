package com.ayshriv.selfhostcrm.web;

import com.ayshriv.selfhostcrm.domain.Role;
import java.time.Instant;

/** A pending invitation as listed. Never contains the token or its hash. {@code invitedByName} is null if the inviter was deleted. */
public record InvitationResponse(Long id, String email, Role role, Long invitedById, String invitedByName,
		Instant expiresAt, Instant createdOn) {
}
