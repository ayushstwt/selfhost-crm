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
 * Audit trail of user actions. The user (the actor) is always set server-side. Never store passwords,
 * tokens or request bodies here.
 *
 * <p>Append-only and never soft-deleted, so there is deliberately no {@code @SQLRestriction} on this
 * entity (unlike {@link User}). The actor row may be soft-deleted later: read the log through DTO
 * projections with a left join instead of loading {@link #getUser()}.
 */
@Entity
@Table(name = "user_log", indexes = {
		@Index(name = "idx_user_log_user_id_created_on", columnList = "user_id, created_on"),
		@Index(name = "idx_user_log_created_on", columnList = "created_on"),
		@Index(name = "idx_user_log_target", columnList = "target_type, target_id") })
@Getter
@Setter
public class UserLog extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	/** Same column as {@link #user}, read-only: lets queries filter and project the actor id without a join. */
	@Column(name = "user_id", insertable = false, updatable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "action", nullable = false, length = 40)
	private UserAction action;

	@Enumerated(EnumType.STRING)
	@Column(name = "sub_action", nullable = false, length = 40)
	private UserSubAction subAction;

	@Enumerated(EnumType.STRING)
	@Column(name = "target_type", length = 20)
	private AuditTargetType targetType;

	@Column(name = "target_id")
	private Long targetId;

	/** The user a team-member action was about (the target is then the team). Null for every other action. */
	@Column(name = "related_user_id")
	private Long relatedUserId;

}
