package com.ayshriv.selfhostcrm.web.admin;

import com.ayshriv.selfhostcrm.config.security.TeamAccessPolicy;
import com.ayshriv.selfhostcrm.config.security.UserPrincipal;
import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import com.ayshriv.selfhostcrm.domain.Permission;
import com.ayshriv.selfhostcrm.domain.RolePermissions;
import com.ayshriv.selfhostcrm.domain.TeamRole;
import com.ayshriv.selfhostcrm.exception.BaseException;
import com.ayshriv.selfhostcrm.exception.ServiceException;
import com.ayshriv.selfhostcrm.service.TeamService;
import com.ayshriv.selfhostcrm.web.TeamMemberResponse;
import com.ayshriv.selfhostcrm.web.TeamResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Team pages for every role (each sees what {@link TeamAccessPolicy} allows). Thin: {@link TeamService}
 * enforces permissions and team rules; the flags only decide which controls are drawn.
 */
@Controller
@RequestMapping("/admin/teams")
public class AdminTeamsController {

	private static final int PAGE_SIZE = 20;

	private static final int MEMBER_PAGE_SIZE = 50;

	private final TeamService teams;

	private final TeamAccessPolicy policy;

	public AdminTeamsController(TeamService teams, TeamAccessPolicy policy) {
		this.teams = teams;
		this.policy = policy;
	}

	/** A member as shown in the table, with whether the actor may change them. */
	public record MemberRow(TeamMemberResponse member, boolean canChange) {
	}

	/** Values of the create / rename form, kept when it is shown again with errors. */
	public record Form(String name, String description) {
	}

	@GetMapping
	public String list(@AuthenticationPrincipal UserPrincipal actor, @RequestParam(required = false) String q,
			@RequestParam(required = false) String page, Model model, HttpServletRequest request) {
		Page<TeamResponse> result = teams.list(actor, q, HtmxSupport.intParam(page, 0), PAGE_SIZE);
		model.addAttribute("teams", result.getContent());
		model.addAttribute("page", result);
		model.addAttribute("q", q == null ? "" : q);
		if (HtmxSupport.isPartial(request)) {
			return "admin/teams :: table";
		}
		model.addAttribute("canCreate", RolePermissions.has(actor.getRole(), Permission.TEAM_MANAGE));
		model.addAttribute("errors", Map.of());
		model.addAttribute("form", new Form("", ""));
		return "admin/teams";
	}

	@PostMapping
	public String create(@AuthenticationPrincipal UserPrincipal actor, @RequestParam(defaultValue = "") String name,
			@RequestParam(defaultValue = "") String description, Model model, HttpServletRequest request,
			HttpServletResponse response) {
		Map<String, String> errors = validate(name, description);
		if (errors.isEmpty()) {
			try {
				TeamResponse created = teams.create(actor, name, description);
				return HtmxSupport.redirect(request, response, "/admin/teams/" + created.id());
			}
			catch (BaseException ex) {
				if (ex.getErrorMessage() != ErrorMessages.E020) {
					throw ex;
				}
				errors.put("name", ex.getMessage());
				response.setStatus(ex.getErrorMessage().getHttpStatus().value());
			}
		}
		else {
			response.setStatus(422);
		}
		model.addAttribute("errors", errors);
		model.addAttribute("form", new Form(name, description));
		return "admin/teams :: createForm";
	}

	@GetMapping("/{id}")
	public String detail(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id,
			@RequestParam(required = false) String page, Model model) {
		TeamResponse team = teams.get(actor, id);
		model.addAttribute("team", team);
		model.addAttribute("canRename", RolePermissions.has(actor.getRole(), Permission.TEAM_MANAGE));
		model.addAttribute("errors", Map.of());
		model.addAttribute("form", new Form(team.name(), team.description() == null ? "" : team.description()));
		memberModel(actor, id, HtmxSupport.intParam(page, 0), model);
		return "admin/team";
	}

	@GetMapping("/{id}/members")
	public String members(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id,
			@RequestParam(required = false) String page, Model model) {
		memberModel(actor, id, HtmxSupport.intParam(page, 0), model);
		return "admin/team :: members";
	}

	@PostMapping("/{id}/rename")
	public String rename(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id,
			@RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String description,
			Model model, HttpServletResponse response) {
		Map<String, String> errors = validate(name, description);
		model.addAttribute("canRename", true);
		if (errors.isEmpty()) {
			try {
				model.addAttribute("team", teams.update(actor, id, name, description));
				model.addAttribute("errors", Map.of());
				model.addAttribute("form", new Form(name.trim(), description.trim()));
				return "admin/team :: header";
			}
			catch (BaseException ex) {
				if (ex.getErrorMessage() != ErrorMessages.E020) {
					throw ex;
				}
				errors.put("name", ex.getMessage());
				response.setStatus(ex.getErrorMessage().getHttpStatus().value());
			}
		}
		else {
			response.setStatus(422);
		}
		model.addAttribute("team", teams.get(actor, id));
		model.addAttribute("errors", errors);
		model.addAttribute("form", new Form(name, description));
		return "admin/team :: header";
	}

	@PostMapping("/{id}/delete")
	public String delete(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id,
			HttpServletRequest request, HttpServletResponse response) {
		teams.delete(actor, id);
		return HtmxSupport.redirect(request, response, "/admin/teams");
	}

	@PostMapping("/{id}/members")
	public String addMember(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id,
			@RequestParam(defaultValue = "") String userId, @RequestParam(defaultValue = "MEMBER") String teamRole,
			Model model) {
		teams.setMember(actor, id, parseUserId(userId), parseRole(teamRole));
		memberModel(actor, id, 0, model);
		return "admin/team :: members";
	}

	@PostMapping("/{id}/members/{userId}/role")
	public String changeRole(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id,
			@PathVariable Long userId, @RequestParam(defaultValue = "") String teamRole, Model model) {
		teams.setMember(actor, id, userId, parseRole(teamRole));
		memberModel(actor, id, 0, model);
		return "admin/team :: members";
	}

	@PostMapping("/{id}/members/{userId}/remove")
	public String remove(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id,
			@PathVariable Long userId, Model model) {
		teams.removeMember(actor, id, userId);
		memberModel(actor, id, 0, model);
		return "admin/team :: members";
	}

	private void memberModel(UserPrincipal actor, Long teamId, int page, Model model) {
		Page<TeamMemberResponse> result = teams.listMembers(actor, teamId, page, MEMBER_PAGE_SIZE);
		boolean canManage = policy.canManageMembers(actor, teamId);
		List<MemberRow> rows = result.getContent().stream().map(m -> new MemberRow(m,
				canManage && policy.canChangeMember(actor, m.userId(), m.accountRole()))).toList();
		model.addAttribute("teamId", teamId);
		model.addAttribute("rows", rows);
		model.addAttribute("memberPage", result);
		model.addAttribute("canManageMembers", canManage);
		model.addAttribute("teamRoles", TeamRole.values());
	}

	private static Map<String, String> validate(String name, String description) {
		Map<String, String> errors = new LinkedHashMap<>();
		if (name.isBlank()) {
			errors.put("name", "Name is required");
		}
		else if (name.trim().length() > 100) {
			errors.put("name", "Name is too long (100 characters at most)");
		}
		if (description.trim().length() > 500) {
			errors.put("description", "Description is too long (500 characters at most)");
		}
		return errors;
	}

	private static Long parseUserId(String value) {
		try {
			return Long.valueOf(value.trim());
		}
		catch (NumberFormatException ex) {
			throw new ServiceException(ErrorMessages.E005);
		}
	}

	private static TeamRole parseRole(String value) {
		TeamRole role = HtmxSupport.parseEnum(TeamRole.class, value);
		if (role == null) {
			throw new ServiceException(ErrorMessages.E005);
		}
		return role;
	}

}
