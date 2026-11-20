package com.ayshriv.selfhostcrm.web;

import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import com.ayshriv.selfhostcrm.web.SetupRequest;
import com.ayshriv.selfhostcrm.exception.ServiceException;
import com.ayshriv.selfhostcrm.service.SetupService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** First-run setup page. Returns 404 permanently once any user exists. */
@Controller
@RequestMapping("/setup")
public class SetupController {

	private final SetupService setupService;

	public SetupController(SetupService setupService) {
		this.setupService = setupService;
	}

	@GetMapping
	public String form(Model model) {
		setupService.assertSetupAllowed();
		model.addAttribute("displayName", "");
		model.addAttribute("email", "");
		model.addAttribute("errors", Map.of());
		return "setup";
	}

	@PostMapping
	public String submit(@Valid @ModelAttribute SetupRequest request, BindingResult binding, Model model) {
		setupService.assertSetupAllowed();
		Map<String, String> errors = new LinkedHashMap<>();
		binding.getFieldErrors().forEach(e -> errors.putIfAbsent(e.getField(), e.getDefaultMessage()));
		if (!errors.containsKey("password") && request.password() != null
				&& !request.password().equals(request.confirmPassword())) {
			errors.put("confirmPassword", "Passwords do not match");
		}
		if (errors.isEmpty()) {
			try {
				setupService.createFirstAdmin(request.displayName(), request.email(), request.password());
				return "redirect:/login?setup";
			}
			catch (ServiceException ex) {
				if (ex.getErrorMessage() != ErrorMessages.E011 && ex.getErrorMessage() != ErrorMessages.E005) {
					throw ex;
				}
				errors.put("password", ex.getMessage());
			}
		}
		model.addAttribute("displayName", request.displayName() == null ? "" : request.displayName());
		model.addAttribute("email", request.email() == null ? "" : request.email());
		model.addAttribute("errors", errors);
		return "setup";
	}

}
