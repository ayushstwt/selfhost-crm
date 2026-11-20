package com.ayshriv.selfhostcrm.web;

import com.ayshriv.selfhostcrm.config.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

	@GetMapping("/")
	public String home(@AuthenticationPrincipal UserPrincipal principal, Model model) {
		model.addAttribute("displayName", principal.getDisplayName());
		model.addAttribute("role", principal.getRole());
		return "home";
	}

}
