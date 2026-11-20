package com.ayshriv.selfhostcrm.web;

import com.ayshriv.selfhostcrm.config.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

	/** Form login is processed by Spring Security; this only renders the page. */
	@GetMapping("/login")
	public String login(@AuthenticationPrincipal UserPrincipal principal) {
		return principal != null ? "redirect:/" : "login";
	}

}
