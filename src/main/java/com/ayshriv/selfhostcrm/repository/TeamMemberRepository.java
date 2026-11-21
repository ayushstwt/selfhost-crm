package com.ayshriv.selfhostcrm.repository;

import com.ayshriv.selfhostcrm.config.security.TeamMembership;
import com.ayshriv.selfhostcrm.domain.TeamMember;
import com.ayshriv.selfhostcrm.web.TeamMemberResponse;
import com.ayshriv.selfhostcrm.web.TeamResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Membership reads are DTO projections that join {@code users} (never a lazy User) and show only live,
 * enabled users. The lookup by user id is the cheap, reusable base for later team-scoped visibility.
 */
public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {

	String VISIBLE_MEMBER = " from TeamMember m, User u where u.id = m.userId and u.deleted = false and u.enabled = true ";

	Optional<TeamMember> findByTeamIdAndUserId(Long teamId, Long userId);

	/** The caller's memberships in live teams (one query per request; used to build the principal). */
	@Query("select new com.ayshriv.selfhostcrm.config.security.TeamMembership(m.teamId, m.teamRole) "
			+ "from TeamMember m join Team t on t.id = m.teamId "
			+ "where m.userId = :userId and m.deleted = false and t.deleted = false")
	List<TeamMembership> findMemberships(@Param("userId") Long userId);

	/** Members of one team, with the user's name, email and account role. Email visibility is decided by the service. */
	@Query(value = "select new com.ayshriv.selfhostcrm.web.TeamMemberResponse(u.id, u.displayName, u.email, u.role, "
			+ "m.teamRole, m.createdOn)" + VISIBLE_MEMBER
			+ "and m.teamId = :teamId order by u.displayName asc, u.id asc",
			countQuery = "select count(m)" + VISIBLE_MEMBER + "and m.teamId = :teamId")
	Page<TeamMemberResponse> findMembers(@Param("teamId") Long teamId, Pageable pageable);

	@Query("select new com.ayshriv.selfhostcrm.web.TeamMemberResponse(u.id, u.displayName, u.email, u.role, "
			+ "m.teamRole, m.createdOn)" + VISIBLE_MEMBER + "and m.teamId = :teamId and m.userId = :userId")
	Optional<TeamMemberResponse> findMember(@Param("teamId") Long teamId, @Param("userId") Long userId);

	@Query("select count(m)" + VISIBLE_MEMBER + "and m.teamId = :teamId")
	long countMembers(@Param("teamId") Long teamId);

	/** Teams of one user (for {@code GET /api/users/{id}/teams}); includes the member count per team. */
	@Query(value = "select new com.ayshriv.selfhostcrm.web.TeamResponse(t.id, t.name, t.description, t.createdOn, "
			+ "(select count(x) from TeamMember x, User xu where xu.id = x.userId and xu.deleted = false "
			+ "and xu.enabled = true and x.teamId = t.id)) "
			+ "from TeamMember m join Team t on t.id = m.teamId "
			+ "where m.userId = :userId and m.deleted = false and t.deleted = false order by t.name asc, t.id asc",
			countQuery = "select count(m) from TeamMember m join Team t on t.id = m.teamId "
					+ "where m.userId = :userId and m.deleted = false and t.deleted = false")
	Page<TeamResponse> findTeamsOfUser(@Param("userId") Long userId, Pageable pageable);

	/** Ids of members of a team whose team role is LEAD (live accounts only are not required: used for access cut-off). */
	@Query("select m.userId from TeamMember m where m.teamId = :teamId and m.teamRole = "
			+ "com.ayshriv.selfhostcrm.domain.TeamRole.LEAD")
	List<Long> findLeadUserIds(@Param("teamId") Long teamId);

	/** Soft-deletes every membership of a team. Bulk update: {@code updated_on} is set explicitly. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update TeamMember m set m.deleted = true, m.updatedOn = :now where m.teamId = :teamId and m.deleted = false")
	int softDeleteByTeam(@Param("teamId") Long teamId, @Param("now") Instant now);

}
