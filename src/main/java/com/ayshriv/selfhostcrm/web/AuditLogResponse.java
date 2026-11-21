package com.ayshriv.selfhostcrm.web;

import com.ayshriv.selfhostcrm.domain.AuditTargetType;
import com.ayshriv.selfhostcrm.domain.UserAction;
import com.ayshriv.selfhostcrm.domain.UserSubAction;
import java.time.Instant;

/**
 * One audit entry. {@code actorName} is null when the acting user has since been deleted. {@code relatedUserId} is
 * the affected user of a team member action (the target is then the team); null for every other action.
 */
public record AuditLogResponse(Long id, Instant createdOn, UserAction action, UserSubAction subAction,
		Long actorId, String actorName, AuditTargetType targetType, Long targetId, Long relatedUserId) {
}
