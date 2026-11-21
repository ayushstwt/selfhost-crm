package com.ayshriv.selfhostcrm.service;

import com.ayshriv.selfhostcrm.domain.AuditTargetType;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.domain.UserAction;
import com.ayshriv.selfhostcrm.domain.UserLog;
import com.ayshriv.selfhostcrm.domain.UserSubAction;
import com.ayshriv.selfhostcrm.repository.UserLogRepository;
import com.ayshriv.selfhostcrm.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Writes audit entries. The user is always supplied by server-side code, never by a request. */
@Service
public class UserLogService {

	private final UserLogRepository logs;

	private final UserRepository users;

	public UserLogService(UserLogRepository logs, UserRepository users) {
		this.logs = logs;
		this.users = users;
	}

	@Transactional
	public void record(User user, UserAction action, UserSubAction subAction) {
		UserLog entry = new UserLog();
		entry.setUser(user);
		entry.setAction(action);
		entry.setSubAction(subAction);
		logs.save(entry);
	}

	/**
	 * Records a successful administrative action by {@code actorId} on a target. Joins the caller's
	 * transaction, so the audit row commits or rolls back together with the change it describes.
	 */
	@Transactional
	public void recordAction(Long actorId, UserAction action, AuditTargetType targetType, Long targetId) {
		recordAction(actorId, action, targetType, targetId, null);
	}

	/** Same, for team member actions: the target is the team, {@code relatedUserId} the affected user. */
	@Transactional
	public void recordAction(Long actorId, UserAction action, AuditTargetType targetType, Long targetId,
			Long relatedUserId) {
		UserLog entry = new UserLog();
		entry.setUser(users.getReferenceById(actorId));
		entry.setAction(action);
		entry.setSubAction(UserSubAction.SUCCESS);
		entry.setTargetType(targetType);
		entry.setTargetId(targetId);
		entry.setRelatedUserId(relatedUserId);
		logs.save(entry);
	}

	@Transactional
	public void recordForUserId(Long userId, UserAction action, UserSubAction subAction) {
		users.findById(userId).ifPresent(user -> record(user, action, subAction));
	}

}
