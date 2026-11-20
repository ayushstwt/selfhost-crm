package com.ayshriv.selfhostcrm.exception;

import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Test-only controller used to exercise the exception handlers. */
@RestController
@RequestMapping("/api/test")
@Validated
class TestApiController {

	record Body(@NotBlank String name, @Email String email) {
	}

	@PostMapping("/validate")
	String validate(@Valid @RequestBody Body body) {
		return "ok";
	}

	@GetMapping("/notfound/{id}")
	String notFound(@PathVariable long id) {
		throw new ResourceNotFoundException(ErrorMessages.E001);
	}

	@GetMapping("/positive")
	String positive(@RequestParam @Positive int n) {
		return "ok";
	}

	@GetMapping("/positive-enum")
	String positiveEnum(@RequestParam @Positive(message = "E004") int n) {
		return "ok";
	}

	@GetMapping("/boom")
	String boom() {
		throw new IllegalStateException("secret-internal-detail");
	}

	@GetMapping("/denied")
	String denied() {
		throw new AccessDeniedException("nope");
	}

	@GetMapping("/param")
	String param(@RequestParam String required) {
		return required;
	}

	@GetMapping("/post-only")
	String get() {
		return "ok";
	}

	@PostMapping(value = "/json-only", consumes = "application/json")
	String jsonOnly(@RequestBody Body body) {
		return "ok";
	}

}
