package com.ayshriv.selfhostcrm.service;

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

	@Transactional
	public void recordForUserId(Long userId, UserAction action, UserSubAction subAction) {
		users.findById(userId).ifPresent(user -> record(user, action, subAction));
	}

}
