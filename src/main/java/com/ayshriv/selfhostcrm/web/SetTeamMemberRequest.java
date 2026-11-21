package com.ayshriv.selfhostcrm.web;

import com.ayshriv.selfhostcrm.domain.TeamRole;
import jakarta.validation.constraints.NotNull;

public record SetTeamMemberRequest(@NotNull(message = "Team role is required") TeamRole teamRole) {
}
