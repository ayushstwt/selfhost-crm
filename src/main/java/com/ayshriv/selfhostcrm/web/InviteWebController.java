package com.ayshriv.selfhostcrm.web;

import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import com.ayshriv.selfhostcrm.exception.ServiceException;
import com.ayshriv.selfhostcrm.service.InvitationService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Minimal public page to accept an invitation. The token is in the URL, so the page is never cached and
 * sends no Referer. Every unusable link (unknown, expired, used, revoked) shows the same error page.
 */
@Controller
@RequestMapping("/invite/{token}")
public class InviteWebController {

	private final InvitationService invitationService;

	public InviteWebController(InvitationService invitationService) {
		this.invitationService = invitationService;
	}

	@GetMapping
	public String form(@PathVariable String token, Model model, HttpServletResponse response) {
		protect(response);
		String email = invitationService.pendingEmail(token)
				.orElseThrow(() -> new ServiceException(ErrorMessages.E016));
		fill(model, email, "", Map.of());
		return "invite";
	}

	@PostMapping
	public String accept(@PathVariable String token, @RequestParam(defaultValue = "") String displayName,
			@RequestParam(defaultValue = "") String password, @RequestParam(defaultValue = "") String confirmPassword,
			Model model, HttpServletResponse response) {
		protect(response);
		String email = invitationService.pendingEmail(token)
				.orElseThrow(() -> new ServiceException(ErrorMessages.E016));
		Map<String, String> errors = new LinkedHashMap<>();
		if (displayName.isBlank()) {
			errors.put("displayName", "Display name is required");
		}
		else if (displayName.trim().length() > 100) {
			errors.put("displayName", "Display name is too long");
		}
		if (password.length() < 12 || password.length() > 128) {
			errors.put("password", "Password must be 12 to 128 characters");
		}
		else if (!password.equals(confirmPassword)) {
			errors.put("confirmPassword", "Passwords do not match");
		}
		if (errors.isEmpty()) {
			try {
				invitationService.accept(token, displayName, password);
				return "redirect:/login?invited";
			}
			catch (ServiceException ex) {
				if (ex.getErrorMessage() != ErrorMessages.E011 && ex.getErrorMessage() != ErrorMessages.E005) {
					throw ex; // E016 (link used or revoked meanwhile): the generic error page
				}
				errors.put("password", ex.getMessage());
			}
		}
		fill(model, email, displayName, errors);
		return "invite";
	}

	private static void fill(Model model, String email, String displayName, Map<String, String> errors) {
		model.addAttribute("email", email);
		model.addAttribute("displayName", displayName);
		model.addAttribute("errors", errors);
	}

	private static void protect(HttpServletResponse response) {
		response.setHeader("Referrer-Policy", "no-referrer");
		response.setHeader("Cache-Control", "no-store");
	}

}
