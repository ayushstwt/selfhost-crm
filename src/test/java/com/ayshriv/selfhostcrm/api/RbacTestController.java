package com.ayshriv.selfhostcrm.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoint (lives in src/test, never shipped) to exercise the 403 path. */
@RestController
@RequestMapping("/api/rbac-test")
class RbacTestController {

	@GetMapping("/admin")
	@PreAuthorize("hasRole('ADMIN')")
	String admin() {
		return "admin only";
	}

}
