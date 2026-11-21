package com.ayshriv.selfhostcrm.web.admin;

import com.ayshriv.selfhostcrm.config.security.UserAccessPolicy;
import com.ayshriv.selfhostcrm.config.security.UserPrincipal;
import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.exception.BaseException;
import com.ayshriv.selfhostcrm.exception.ServiceException;
import com.ayshriv.selfhostcrm.service.InvitationService;
import com.ayshriv.selfhostcrm.service.UserManagementService;
import com.ayshriv.selfhostcrm.web.CreatedInvitationResponse;
import com.ayshriv.selfhostcrm.web.UpdateUserRequest;
import com.ayshriv.selfhostcrm.web.UserResponse;
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
 * Admin pages for users and invitations (ADMIN and MANAGER). Thin: every rule is enforced by the services and
 * {@link UserAccessPolicy}; the {@code can*} flags only decide which buttons are drawn. Every response that
 * contains a one-time link is sent with {@code Cache-Control: no-store}.
 */
@Controller
@RequestMapping("/admin")
public class AdminUsersController {

	private static final int PAGE_SIZE = 20;

	private final UserManagementService users;

	private final InvitationService invitations;

	private final UserAccessPolicy policy;

	public AdminUsersController(UserManagementService users, InvitationService invitations, UserAccessPolicy policy) {
		this.users = users;
		this.invitations = invitations;
		this.policy = policy;
	}

	/** A user as shown in the table, with the actions the actor may take on them. */
	public record UserRow(UserResponse user, boolean canToggle, boolean canChangeRole, boolean canDelete) {
	}

	@GetMapping("/users")
	public String list(@AuthenticationPrincipal UserPrincipal actor, @RequestParam(required = false) String q,
			@RequestParam(required = false) String role, @RequestParam(required = false) String enabled,
			@RequestParam(required = false) String page, Model model, HttpServletRequest request) {
		Role roleFilter = HtmxSupport.parseEnum(Role.class, role);
		Boolean enabledFilter = HtmxSupport.parseBoolean(enabled);
		Page<UserResponse> result = users.list(roleFilter, enabledFilter, q, HtmxSupport.intParam(page, 0), PAGE_SIZE);
		model.addAttribute("rows", result.getContent().stream().map(u -> row(actor, u)).toList());
		model.addAttribute("page", result);
		model.addAttribute("q", q == null ? "" : q);
		model.addAttribute("roleFilter", roleFilter == null ? "" : roleFilter.name());
		model.addAttribute("enabledFilter", enabledFilter == null ? "" : enabledFilter.toString());
		model.addAttribute("roles", Role.values());
		if (HtmxSupport.isPartial(request)) {
			return "admin/users :: table";
		}
		inviteModel(actor, model);
		model.addAttribute("invitations", invitations.listPending(actor, 0, 50).getContent());
		return "admin/users";
	}

	@PostMapping("/users/{id}/enabled")
	public String setEnabled(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id,
			@RequestParam(defaultValue = "") String enabled, Model model) {
		Boolean value = HtmxSupport.parseBoolean(enabled);
		if (value == null) {
			throw new ServiceException(ErrorMessages.E005);
		}
		users.update(actor, id, new UpdateUserRequest(null, value));
		return rowFragment(actor, id, model);
	}

	@PostMapping("/users/{id}/role")
	public String setRole(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id,
			@RequestParam(defaultValue = "") String role, Model model) {
		Role value = HtmxSupport.parseEnum(Role.class, role);
		if (value == null) {
			throw new ServiceException(ErrorMessages.E005);
		}
		users.changeRole(actor, id, value);
		return rowFragment(actor, id, model);
	}

	/** Responds with an empty body so the table row is swapped out. */
	@PostMapping("/users/{id}/delete")
	public String delete(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
		users.delete(actor, id);
		return HtmxSupport.EMPTY;
	}

	/** Options for the searchable user select (team member picker, audit actor filter). */
	@GetMapping("/users/search")
	public String search(@AuthenticationPrincipal UserPrincipal actor, @RequestParam(required = false) String q,
			@RequestParam(defaultValue = "false") String blank, @RequestParam(defaultValue = "false") String enabledOnly,
			Model model) {
		// A Manager may only add Member accounts to a team, so only those are offered.
		Role role = actor.getRole() == Role.MANAGER ? Role.MEMBER : null;
		Boolean enabled = "true".equals(enabledOnly) ? Boolean.TRUE : null;
		model.addAttribute("options", users.list(role, enabled, q, 0, PAGE_SIZE).getContent());
		model.addAttribute("blank", "true".equals(blank));
		return "admin/users-fragments :: options";
	}

	@GetMapping("/invitations")
	public String invitationList(@AuthenticationPrincipal UserPrincipal actor, Model model) {
		model.addAttribute("invitations", invitations.listPending(actor, 0, 50).getContent());
		return "admin/users :: invitations";
	}

	@PostMapping("/invitations/{id}/revoke")
	public String revoke(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id, Model model) {
		invitations.revoke(actor, id);
		model.addAttribute("invitations", invitations.listPending(actor, 0, 50).getContent());
		return "admin/users :: invitations";
	}

	/**
	 * Creates the invitation and shows the one-time link in the response only. It is not stored in the session,
	 * a flash attribute or the log; a reload shows nothing.
	 */
	@PostMapping("/invitations")
	public String invite(@AuthenticationPrincipal UserPrincipal actor, @RequestParam(defaultValue = "") String email,
			@RequestParam(defaultValue = "") String role, Model model, HttpServletResponse response) {
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("Referrer-Policy", "no-referrer");
		inviteModel(actor, model);
		Map<String, String> errors = new LinkedHashMap<>();
		String trimmed = email.trim();
		if (trimmed.isEmpty()) {
			errors.put("email", "Email is required");
		}
		else if (trimmed.length() > 254 || !trimmed.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
			errors.put("email", "Enter a valid email address");
		}
		Role invitedRole = HtmxSupport.parseEnum(Role.class, role);
		if (invitedRole == null) {
			errors.put("role", "Role is required");
		}
		model.addAttribute("email", trimmed);
		if (errors.isEmpty()) {
			try {
				CreatedInvitationResponse created = invitations.create(actor, trimmed, invitedRole);
				response.setHeader("HX-Trigger", "invitationsChanged");
				model.addAttribute("created", created);
				model.addAttribute("errors", Map.of());
				model.addAttribute("email", "");
				return "admin/users-fragments :: inviteResult";
			}
			catch (BaseException ex) {
				if (ex.getErrorMessage().getHttpStatus().is5xxServerError()) {
					throw ex;
				}
				response.setStatus(ex.getErrorMessage().getHttpStatus().value());
				errors.put(ex.getErrorMessage() == ErrorMessages.E013 ? "role" : "email", ex.getMessage());
			}
		}
		else {
			response.setStatus(422);
		}
		model.addAttribute("errors", errors);
		return "admin/users :: inviteForm";
	}

	private void inviteModel(UserPrincipal actor, Model model) {
		model.addAttribute("inviteRoles", actor.getRole() == Role.ADMIN ? List.of(Role.values()) : List.of(Role.MEMBER));
		if (!model.containsAttribute("errors")) {
			model.addAttribute("errors", Map.of());
		}
		if (!model.containsAttribute("email")) {
			model.addAttribute("email", "");
		}
	}

	private String rowFragment(UserPrincipal actor, Long id, Model model) {
		model.addAttribute("roles", Role.values());
		model.addAttribute("row", row(actor, users.get(id)));
		return "admin/users-fragments :: row";
	}

	private UserRow row(UserPrincipal actor, UserResponse user) {
		return new UserRow(user, policy.canToggleEnabled(actor, user.id(), user.role()),
				policy.canChangeRole(actor, user.id()), policy.canDelete(actor, user.id()));
	}

}
