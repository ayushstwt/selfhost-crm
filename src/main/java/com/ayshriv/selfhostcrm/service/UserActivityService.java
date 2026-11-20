package com.ayshriv.selfhostcrm.service;

import com.ayshriv.selfhostcrm.repository.UserLogRepository;
import com.ayshriv.selfhostcrm.web.UserLogResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read access to a user's own audit trail. Admin-wide audit comes later with RBAC. */
@Service
public class UserActivityService {

	private final UserLogRepository logs;

	public UserActivityService(UserLogRepository logs) {
		this.logs = logs;
	}

	/** Newest first; the ordering lives in the query. Only the given user's entries are returned. */
	@Transactional(readOnly = true)
	public Page<UserLogResponse> findOwn(Long userId, int page, int size) {
		return logs.findResponsesByUserId(userId, PageRequest.of(page, size));
	}

}
