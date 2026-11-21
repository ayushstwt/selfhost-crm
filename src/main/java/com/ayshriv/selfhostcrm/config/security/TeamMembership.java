package com.ayshriv.selfhostcrm.config.security;

import com.ayshriv.selfhostcrm.domain.TeamRole;
import java.io.Serializable;

/** One team the caller belongs to, with their role in it. Carried by {@link UserPrincipal}. */
public record TeamMembership(Long teamId, TeamRole teamRole) implements Serializable {
}
