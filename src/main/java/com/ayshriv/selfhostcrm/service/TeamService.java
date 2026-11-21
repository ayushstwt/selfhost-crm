package com.ayshriv.selfhostcrm.service;

import com.ayshriv.selfhostcrm.config.security.TeamAccessPolicy;
import com.ayshriv.selfhostcrm.config.security.UserPrincipal;
import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import com.ayshriv.selfhostcrm.domain.AuditTargetType;
import com.ayshriv.selfhostcrm.domain.Permission;
import com.ayshriv.selfhostcrm.domain.RolePermissions;
import com.ayshriv.selfhostcrm.domain.Team;
import com.ayshriv.selfhostcrm.domain.TeamMember;
import com.ayshriv.selfhostcrm.domain.TeamRole;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.domain.UserAction;
import com.ayshriv.selfhostcrm.exception.ResourceAlreadyExistException;
import com.ayshriv.selfhostcrm.exception.ResourceNotFoundException;
import com.ayshriv.selfhostcrm.exception.ServiceException;
import com.ayshriv.selfhostcrm.repository.TeamMemberRepository;
import com.ayshriv.selfhostcrm.repository.TeamRepository;
import com.ayshriv.selfhostcrm.repository.UserRepository;
import com.ayshriv.selfhostcrm.web.TeamMemberResponse;
import com.ayshriv.selfhostcrm.web.TeamResponse;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teams and team membership. Endpoint permissions are {@code @PreAuthorize}; which team and which user is
 * {@link TeamAccessPolicy}, called before the team is loaded (a non-admin gets 403 for a team they are not in,
 * existing or not). Every change and its audit row share one transaction. Member changes lock the team row
 * first and then the user row (always in that order).
 *
 * <p>Access cut-off rule: rights are read from the database on every request, so a membership change takes
 * effect on the next request on its own. {@code access_changed_at} is bumped (tokens and sessions die, refresh
 * tokens are revoked) only when someone loses management rights: removed from a team as LEAD, demoted from
 * LEAD, or a team they led is deleted. Grants and plain-member removals do not bump.
 */
@Service
public class TeamService {

	private final TeamRepository teams;

	private final TeamMemberRepository members;

	private final UserRepository users;

	private final TeamAccessPolicy policy;

	private final AccessRevocationService revocation;

	private final UserLogService userLog;

	public TeamService(TeamRepository teams, TeamMemberRepository members, UserRepository users,
			TeamAccessPolicy policy, AccessRevocationService revocation, UserLogService userLog) {
		this.teams = teams;
		this.members = members;
		this.users = users;
		this.policy = policy;
		this.revocation = revocation;
		this.userLog = userLog;
	}

	/** ADMIN: all live teams. Everyone else: the teams they belong to. */
	@PreAuthorize("isAuthenticated()")
	@Transactional(readOnly = true)
	public Page<TeamResponse> list(UserPrincipal actor, String search, int page, int size) {
		Pageable pageable = PageRequest.of(page, size);
		String pattern = likePattern(search);
		if (policy.canViewAllTeams(actor)) {
			return teams.searchAll(pattern, pageable);
		}
		Set<Long> ids = actor.teamIds();
		return ids.isEmpty() ? new PageImpl<>(List.of(), pageable, 0) : teams.searchAmong(ids, pattern, pageable);
	}

	@PreAuthorize("isAuthenticated()")
	@Transactional(readOnly = true)
	public TeamResponse get(UserPrincipal actor, Long id) {
		policy.assertCanView(actor, id);
		return teams.findResponseById(id).orElseThrow(() -> new ResourceNotFoundException(ErrorMessages.E019));
	}

	@PreAuthorize("hasAuthority('TEAM_MANAGE')")
	@Transactional
	public TeamResponse create(UserPrincipal actor, String name, String description) {
		String trimmed = name.trim();
		if (teams.existsByNameIgnoreCase(trimmed)) {
			throw new ResourceAlreadyExistException(ErrorMessages.E020);
		}
		Team team = new Team();
		team.setName(trimmed);
		team.setDescription(cleanDescription(description));
		try {
			team = teams.saveAndFlush(team);
		}
		catch (DataIntegrityViolationException ex) {
			throw new ResourceAlreadyExistException(ErrorMessages.E020);
		}
		userLog.recordAction(actor.getId(), UserAction.TEAM_CREATED, AuditTargetType.TEAM, team.getId());
		return teams.findResponseById(team.getId()).orElseThrow();
	}

	@PreAuthorize("hasAuthority('TEAM_MANAGE')")
	@Transactional
	public TeamResponse update(UserPrincipal actor, Long id, String name, String description) {
		Team team = lockTeam(id);
		boolean changed = false;
		if (name != null && !name.trim().equals(team.getName())) {
			if (teams.existsByNameIgnoreCaseAndIdNot(name.trim(), id)) {
				throw new ResourceAlreadyExistException(ErrorMessages.E020);
			}
			team.setName(name);
			changed = true;
		}
		if (description != null && !Objects.equals(cleanDescription(description), team.getDescription())) {
			team.setDescription(cleanDescription(description));
			changed = true;
		}
		if (changed) {
			try {
				teams.saveAndFlush(team);
			}
			catch (DataIntegrityViolationException ex) {
				throw new ResourceAlreadyExistException(ErrorMessages.E020);
			}
			userLog.recordAction(actor.getId(), UserAction.TEAM_UPDATED, AuditTargetType.TEAM, id);
		}
		return teams.findResponseById(id).orElseThrow();
	}

	/** Soft-deletes the team and all its memberships; former leads lose their management rights at once. */
	@PreAuthorize("hasAuthority('TEAM_MANAGE')")
	@Transactional
	public void delete(UserPrincipal actor, Long id) {
		Team team = lockTeam(id);
		List<Long> leads = members.findLeadUserIds(id);
		team.setDeleted(true);
		teams.saveAndFlush(team);
		userLog.recordAction(actor.getId(), UserAction.TEAM_DELETED, AuditTargetType.TEAM, id);
		members.softDeleteByTeam(id, Instant.now()); // bulk update: flushes first, then clears the context
		revocation.revokeAccess(leads);
	}

	@PreAuthorize("isAuthenticated()")
	@Transactional(readOnly = true)
	public Page<TeamMemberResponse> listMembers(UserPrincipal actor, Long teamId, int page, int size) {
		policy.assertCanView(actor, teamId);
		requireTeam(teamId);
		return members.findMembers(teamId, PageRequest.of(page, size)).map(m -> forViewer(actor, m));
	}

	@PreAuthorize("isAuthenticated()")
	@Transactional(readOnly = true)
	public TeamMemberResponse getMember(UserPrincipal actor, Long teamId, Long userId) {
		policy.assertCanView(actor, teamId);
		requireTeam(teamId);
		return members.findMember(teamId, userId).map(m -> forViewer(actor, m))
				.orElseThrow(() -> new ResourceNotFoundException(ErrorMessages.E021));
	}

	/** Result of {@link #setMember}: created (201) or only changed/kept (200). */
	public record MemberResult(TeamMemberResponse member, boolean created) {
	}

	/** Adds the user to the team or changes their team role (idempotent). */
	@PreAuthorize("hasAuthority('TEAM_MEMBERS_MANAGE')")
	@Transactional
	public MemberResult setMember(UserPrincipal actor, Long teamId, Long userId, TeamRole role) {
		policy.assertCanManageMembers(actor, teamId);
		lockTeam(teamId);
		User target = users.findForUpdateById(userId)
				.orElseThrow(() -> new ResourceNotFoundException(ErrorMessages.E001));
		policy.assertCanChangeMember(actor, userId, target.getRole());
		if (!target.isEnabled()) {
			throw new ServiceException(ErrorMessages.E022);
		}
		TeamMember existing = members.findByTeamIdAndUserId(teamId, userId).orElse(null);
		boolean created = existing == null;
		boolean demotedLead = false;
		if (created) {
			TeamMember member = new TeamMember();
			member.setTeamId(teamId);
			member.setUserId(userId);
			member.setTeamRole(role);
			members.saveAndFlush(member);
			userLog.recordAction(actor.getId(), UserAction.TEAM_MEMBER_ADDED, AuditTargetType.TEAM, teamId, userId);
		}
		else if (existing.getTeamRole() != role) {
			demotedLead = existing.getTeamRole() == TeamRole.LEAD;
			existing.setTeamRole(role);
			members.saveAndFlush(existing);
			userLog.recordAction(actor.getId(), UserAction.TEAM_MEMBER_ROLE_CHANGED, AuditTargetType.TEAM, teamId,
					userId);
		}
		if (demotedLead) {
			revocation.revokeAccess(target); // last: flushes, then clears the persistence context
		}
		TeamMemberResponse response = members.findMember(teamId, userId).orElseThrow();
		return new MemberResult(forViewer(actor, response), created);
	}

	@PreAuthorize("hasAuthority('TEAM_MEMBERS_MANAGE')")
	@Transactional
	public void removeMember(UserPrincipal actor, Long teamId, Long userId) {
		policy.assertCanManageMembers(actor, teamId);
		lockTeam(teamId);
		User target = users.findForUpdateById(userId)
				.orElseThrow(() -> new ResourceNotFoundException(ErrorMessages.E001));
		policy.assertCanChangeMember(actor, userId, target.getRole());
		TeamMember member = members.findByTeamIdAndUserId(teamId, userId)
				.orElseThrow(() -> new ResourceNotFoundException(ErrorMessages.E021));
		boolean wasLead = member.getTeamRole() == TeamRole.LEAD;
		members.delete(member);
		userLog.recordAction(actor.getId(), UserAction.TEAM_MEMBER_REMOVED, AuditTargetType.TEAM, teamId, userId);
		if (wasLead) {
			revocation.revokeAccess(target);
		}
	}

	/** The teams of one user. ADMIN: any user. Everyone else: only themselves. */
	@PreAuthorize("isAuthenticated()")
	@Transactional(readOnly = true)
	public Page<TeamResponse> teamsOfUser(UserPrincipal actor, Long userId, int page, int size) {
		policy.assertCanViewTeamsOfUser(actor, userId);
		if (!users.existsById(userId)) {
			throw new ResourceNotFoundException(ErrorMessages.E001);
		}
		return members.findTeamsOfUser(userId, PageRequest.of(page, size));
	}

	private Team lockTeam(Long id) {
		return teams.findForUpdateById(id).orElseThrow(() -> new ResourceNotFoundException(ErrorMessages.E019));
	}

	private void requireTeam(Long id) {
		if (!teams.existsById(id)) {
			throw new ResourceNotFoundException(ErrorMessages.E019);
		}
	}

	/** Emails are shown only to callers who may view users. */
	private static TeamMemberResponse forViewer(UserPrincipal actor, TeamMemberResponse member) {
		return RolePermissions.has(actor.getRole(), Permission.USER_VIEW) ? member : member.withoutEmail();
	}

	private static String cleanDescription(String description) {
		return description == null || description.isBlank() ? null : description.trim();
	}

	/** Lower-cased LIKE pattern with {@code !} as escape character; a blank search matches everything. */
	static String likePattern(String search) {
		if (search == null || search.isBlank()) {
			return "%";
		}
		String escaped = search.trim().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%")
				.replace("_", "!_");
		return "%" + escaped + "%";
	}

}
