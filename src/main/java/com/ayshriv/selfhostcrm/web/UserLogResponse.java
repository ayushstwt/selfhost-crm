package com.ayshriv.selfhostcrm.web;

import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.UserAction;
import com.ayshriv.selfhostcrm.domain.UserSubAction;
import java.time.Instant;

public record UserLogResponse(Long id, Instant createdOn, UserAction action, UserSubAction subAction,
		String userName, Role userRole) {
}
