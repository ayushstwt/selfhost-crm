package com.ayshriv.selfhostcrm.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** Test-only web page (lives in src/test, never shipped) standing in for admin pages that come in the next PR. */
@Controller
class RbacTestWebController {

	@GetMapping("/admin-test")
	@ResponseBody
	@PreAuthorize("hasRole('ADMIN')")
	String adminPage() {
		return "admin page";
	}

}
