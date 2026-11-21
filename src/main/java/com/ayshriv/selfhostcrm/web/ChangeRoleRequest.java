package com.ayshriv.selfhostcrm.web;

import com.ayshriv.selfhostcrm.domain.Role;
import jakarta.validation.constraints.NotNull;

public record ChangeRoleRequest(@NotNull(message = "Role is required") Role role) {
}
