package com.ayshriv.selfhostcrm.web;

import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.TeamRole;
import java.time.Instant;

/**
 * A member of a team. {@code email} is only filled for callers who may view users ({@code USER_VIEW});
 * plain members see {@code null}.
 */
public record TeamMemberResponse(Long userId, String displayName, String email, Role accountRole, TeamRole teamRole,
		Instant joinedOn) {

	public TeamMemberResponse withoutEmail() {
		return new TeamMemberResponse(userId, displayName, null, accountRole, teamRole, joinedOn);
	}

}
