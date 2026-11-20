package com.ayshriv.selfhostcrm.web;

import com.ayshriv.selfhostcrm.config.security.UserPrincipal;
import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import com.ayshriv.selfhostcrm.web.ChangePasswordRequest;
import com.ayshriv.selfhostcrm.exception.ServiceException;
import com.ayshriv.selfhostcrm.service.PasswordChangeService;
import com.ayshriv.selfhostcrm.config.security.ActiveUserSessionFilter;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Web UI for the signed-in user's own account. */
@Controller
@RequestMapping("/account")
public class AccountController {

	private final PasswordChangeService passwordChangeService;

	public AccountController(PasswordChangeService passwordChangeService) {
		this.passwordChangeService = passwordChangeService;
	}

	@GetMapping("/password")
	public String form(Model model) {
		model.addAttribute("errors", Map.of());
		return "account/password";
	}

	@PostMapping("/password")
	public String change(@AuthenticationPrincipal UserPrincipal principal,
			@Valid @ModelAttribute ChangePasswordRequest request, BindingResult binding,
			@RequestParam(defaultValue = "") String confirmPassword, HttpSession session, Model model) {
		Map<String, String> errors = new LinkedHashMap<>();
		binding.getFieldErrors().forEach(e -> errors.putIfAbsent(e.getField(), e.getDefaultMessage()));
		if (!errors.containsKey("newPassword") && request.newPassword() != null
				&& !request.newPassword().equals(confirmPassword)) {
			errors.put("confirmPassword", "Passwords do not match");
		}
		if (errors.isEmpty()) {
			try {
				passwordChangeService.changePassword(principal.getId(), request.currentPassword(),
						request.newPassword());
				// this session proves the new credentials; all other sessions of the user are ended
				session.setAttribute(ActiveUserSessionFilter.AUTHENTICATED_AT, Instant.now());
				return "redirect:/?passwordChanged";
			}
			catch (ServiceException ex) {
				ErrorMessages code = ex.getErrorMessage();
				if (code == ErrorMessages.E012) {
					errors.put("currentPassword", ex.getMessage());
				}
				else if (code == ErrorMessages.E011 || code == ErrorMessages.E005) {
					errors.put("newPassword", ex.getMessage());
				}
				else {
					throw ex;
				}
			}
		}
		model.addAttribute("errors", errors);
		return "account/password";
	}

}
