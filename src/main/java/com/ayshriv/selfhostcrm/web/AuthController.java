package com.ayshriv.selfhostcrm.web;

import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_INVALID_CREDENTIALS;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_LOGIN;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_ME;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_PASSWORD_CHANGED;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.REF;

import com.ayshriv.selfhostcrm.config.security.UserPrincipal;
import com.ayshriv.selfhostcrm.service.AuthService;
import com.ayshriv.selfhostcrm.service.PasswordChangeService;
import com.ayshriv.selfhostcrm.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** JWT API authentication. login, refresh and logout are public; the rest needs a bearer token. */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

	private final AuthService authService;

	private final UserService userService;

	private final PasswordChangeService passwordChangeService;

	public AuthController(AuthService authService, UserService userService,
			PasswordChangeService passwordChangeService) {
		this.authService = authService;
		this.userService = userService;
		this.passwordChangeService = passwordChangeService;
	}

	@Operation(summary = "Sign in with email and password",
			description = "Wrong password, unknown email and a temporarily locked account all return the same 401.")
	@SecurityRequirements
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Tokens issued",
			content = @Content(examples = @ExampleObject(name = EX_LOGIN, ref = REF + EX_LOGIN)))
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Invalid email or password",
			content = @Content(examples = @ExampleObject(name = EX_INVALID_CREDENTIALS,
					ref = REF + EX_INVALID_CREDENTIALS)))
	@PostMapping("/login")
	public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
		return ResponseEntity.ok(ApiResponse.success("Login successful",
				authService.login(request.email(), request.password())));
	}

	@Operation(summary = "Rotate a refresh token",
			description = "Returns a new access and refresh token and revokes the old refresh token. Presenting a "
					+ "revoked token again revokes the whole token family. Expired, unknown and reused tokens "
					+ "all return the same 401.")
	@SecurityRequirements
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Tokens rotated",
			content = @Content(examples = @ExampleObject(name = EX_LOGIN, ref = REF + EX_LOGIN)))
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Invalid or expired refresh token")
	@PostMapping("/refresh")
	public ResponseEntity<ApiResponse<TokenResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
		return ResponseEntity.ok(ApiResponse.success("Token refreshed",
				authService.refresh(request.refreshToken())));
	}

	@Operation(summary = "Revoke a refresh token",
			description = "Public: the refresh token in the body is the credential. Always 204 for a well-formed "
					+ "token, whether or not it existed.")
	@SecurityRequirements
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Done (no body)")
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
		authService.logout(request.refreshToken());
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "Current user")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The signed-in user",
			content = @Content(examples = @ExampleObject(name = EX_ME, ref = REF + EX_ME)))
	@GetMapping("/me")
	public ResponseEntity<ApiResponse<UserResponse>> me(@AuthenticationPrincipal UserPrincipal principal) {
		return ResponseEntity.ok(ApiResponse.success("Current user retrieved successfully",
				userService.getById(principal.getId())));
	}

	@Operation(summary = "Change password",
			description = "Needs the current password. Revokes all refresh tokens of the user and ends their other "
					+ "web sessions.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(
			name = "ChangePasswordRequest",
			value = "{\"currentPassword\":\"your-current-password\",\"newPassword\":\"a-new-password-12+\"}")))
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Password changed",
			content = @Content(examples = @ExampleObject(name = EX_PASSWORD_CHANGED, ref = REF + EX_PASSWORD_CHANGED)))
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
			description = "Validation failed, current password incorrect (E012) or new password equals the email (E011)")
	@PostMapping("/change-password")
	public ResponseEntity<ApiResponse<Void>> changePassword(@AuthenticationPrincipal UserPrincipal principal,
			@Valid @RequestBody ChangePasswordRequest request) {
		passwordChangeService.changePassword(principal.getId(), request.currentPassword(), request.newPassword());
		return ResponseEntity.ok(ApiResponse.success("Password changed. Sign in again on other devices.", null));
	}

}
