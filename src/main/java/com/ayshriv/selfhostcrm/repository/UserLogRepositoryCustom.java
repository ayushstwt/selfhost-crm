package com.ayshriv.selfhostcrm.repository;

import com.ayshriv.selfhostcrm.domain.AuditTargetType;
import com.ayshriv.selfhostcrm.domain.UserAction;
import com.ayshriv.selfhostcrm.web.AuditLogResponse;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Filtered, paginated audit read as a DTO projection (never loads entities). */
public interface UserLogRepositoryCustom {

	/** All filters are optional. Newest first. {@code from} and {@code to} are inclusive. */
	Page<AuditLogResponse> search(Long actorId, UserAction action, AuditTargetType targetType, Long targetId,
			Instant from, Instant to, Pageable pageable);

}
