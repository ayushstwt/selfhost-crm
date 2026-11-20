package com.ayshriv.selfhostcrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Audit trail of user actions. The user is always set server-side. Never store passwords, tokens
 * or request bodies here.
 */
@Entity
@Table(name = "user_log", indexes = @Index(name = "idx_user_log_user_id_created_on",
		columnList = "user_id, created_on"))
@Getter
@Setter
public class UserLog extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(name = "action", nullable = false, length = 40)
	private UserAction action;

	@Enumerated(EnumType.STRING)
	@Column(name = "sub_action", nullable = false, length = 40)
	private UserSubAction subAction;

}
