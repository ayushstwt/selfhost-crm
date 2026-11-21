package com.ayshriv.selfhostcrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

/**
 * Membership of a user in a team. The team and the user are plain ids, never associations: the user may be
 * soft-deleted or disabled later, so reads use DTO projections with a join on {@code users}. A removed member
 * is hard-deleted; a deleted team soft-deletes all its memberships.
 */
@Entity
@Table(name = "team_members")
@SQLRestriction("is_deleted = false")
@Getter
@Setter
public class TeamMember extends BaseEntity {

	@Column(name = "team_id", nullable = false)
	private Long teamId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "team_role", nullable = false, length = 20)
	private TeamRole teamRole = TeamRole.MEMBER;

}
