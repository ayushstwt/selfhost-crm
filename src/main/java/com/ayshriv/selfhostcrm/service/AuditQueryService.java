package com.ayshriv.selfhostcrm.service;

import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import com.ayshriv.selfhostcrm.domain.AuditTargetType;
import com.ayshriv.selfhostcrm.domain.UserAction;
import com.ayshriv.selfhostcrm.exception.ServiceException;
import com.ayshriv.selfhostcrm.repository.UserLogRepository;
import com.ayshriv.selfhostcrm.web.AuditLogResponse;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin-wide audit log (a user's own entries are served by {@link UserActivityService}). */
@Service
public class AuditQueryService {

	private final UserLogRepository logs;

	public AuditQueryService(UserLogRepository logs) {
		this.logs = logs;
	}

	@PreAuthorize("hasAuthority('AUDIT_VIEW_ALL')")
	@Transactional(readOnly = true)
	public Page<AuditLogResponse> search(Long actorId, UserAction action, AuditTargetType targetType, Long targetId,
			Instant from, Instant to, int page, int size) {
		if (from != null && to != null && from.isAfter(to)) {
			throw new ServiceException(ErrorMessages.E005);
		}
		return logs.search(actorId, action, targetType, targetId, from, to, PageRequest.of(page, size));
	}

}
