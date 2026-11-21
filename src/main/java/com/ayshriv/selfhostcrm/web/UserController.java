package com.ayshriv.selfhostcrm.web;

import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_ACCESS_DENIED_TARGET;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_LAST_ADMIN;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_TEAM_PAGE;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_USER;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_USER_PAGE;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.REF;

import com.ayshriv.selfhostcrm.config.security.UserPrincipal;
import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.service.TeamService;
import com.ayshriv.selfhostcrm.service.UserManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** User administration. Thin: permissions and target rules are enforced in {@code UserManagementService}. */
@RestController
@RequestMapping("/api/users")
@Validated
@Tag(name = "Users")
public class UserController {

	private final UserManagementService userService;

	private final TeamService teamService;

	public UserController(UserManagementService userService, TeamService teamService) {
		this.userService = userService;
		this.teamService = teamService;
	}

	@Operation(summary = "List users",
			description = "Admin and Manager. Newest first. Filters: role, enabled, q (name or email contains). size max 100.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "A page of users",
			content = @Content(examples = @ExampleObject(name = EX_USER_PAGE, ref = REF + EX_USER_PAGE)))
	@GetMapping
	public ResponseEntity<ApiResponse<List<UserResponse>>> list(
			@RequestParam(required = false) Role role,
			@RequestParam(required = false) Boolean enabled,
			@RequestParam(name = "q", required = false) String search,
			@RequestParam(defaultValue = "0") @Min(value = 0, message = "page must be 0 or greater") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = "size must be at least 1")
			@Max(value = 100, message = "size must be at most 100") int size) {
		Page<UserResponse> result = userService.list(role, enabled, search, page, size);
		return ResponseEntity.ok(ApiResponse.success("Users retrieved successfully", result.getContent(),
				PageMetadata.from(result)));
	}

	@Operation(summary = "Get a user", description = "Admin and Manager.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The user",
			content = @Content(examples = @ExampleObject(name = EX_USER, ref = REF + EX_USER)))
	@GetMapping("/{id}")
	public ResponseEntity<ApiResponse<UserResponse>> get(@PathVariable Long id) {
		return ResponseEntity.ok(ApiResponse.success("User retrieved successfully", userService.get(id)));
	}

	@Operation(summary = "List the teams of a user",
			description = "Admin: any user. Everyone else: only themselves (403 otherwise). size max 100.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "A page of teams",
			content = @Content(examples = @ExampleObject(name = EX_TEAM_PAGE, ref = REF + EX_TEAM_PAGE)))
	@GetMapping("/{id}/teams")
	public ResponseEntity<ApiResponse<List<TeamResponse>>> teams(@AuthenticationPrincipal UserPrincipal actor,
			@PathVariable Long id,
			@RequestParam(defaultValue = "0") @Min(value = 0, message = "page must be 0 or greater") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = "size must be at least 1")
			@Max(value = 100, message = "size must be at most 100") int size) {
		Page<TeamResponse> result = teamService.teamsOfUser(actor, id, page, size);
		return ResponseEntity.ok(ApiResponse.success("Teams retrieved successfully", result.getContent(),
				PageMetadata.from(result)));
	}

	@Operation(summary = "Update display name or enabled state",
			description = "Admin: any user. Manager: Member accounts (and their own display name). Nobody can "
					+ "disable themselves, and the last enabled Admin cannot be disabled. Disabling or enabling "
					+ "cuts off the user's existing tokens and sessions.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The updated user",
			content = @Content(examples = @ExampleObject(name = EX_USER, ref = REF + EX_USER)))
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not allowed on this user",
			content = @Content(examples = @ExampleObject(name = EX_ACCESS_DENIED_TARGET, ref = REF + EX_ACCESS_DENIED_TARGET)))
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Last enabled Admin",
			content = @Content(examples = @ExampleObject(name = EX_LAST_ADMIN, ref = REF + EX_LAST_ADMIN)))
	@PatchMapping("/{id}")
	public ResponseEntity<ApiResponse<UserResponse>> update(@AuthenticationPrincipal UserPrincipal actor,
			@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
		return ResponseEntity.ok(ApiResponse.success("User updated successfully",
				userService.update(actor, id, request)));
	}

	@Operation(summary = "Change a user's role",
			description = "Admin only. Nobody can change their own role, and the last enabled Admin cannot be "
					+ "demoted. Cuts off the user's existing tokens and sessions.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The updated user",
			content = @Content(examples = @ExampleObject(name = EX_USER, ref = REF + EX_USER)))
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Last enabled Admin",
			content = @Content(examples = @ExampleObject(name = EX_LAST_ADMIN, ref = REF + EX_LAST_ADMIN)))
	@PatchMapping("/{id}/role")
	public ResponseEntity<ApiResponse<UserResponse>> changeRole(@AuthenticationPrincipal UserPrincipal actor,
			@PathVariable Long id, @Valid @RequestBody ChangeRoleRequest request) {
		return ResponseEntity.ok(ApiResponse.success("Role changed successfully",
				userService.changeRole(actor, id, request.role())));
	}

	@Operation(summary = "Delete a user",
			description = "Admin only. Soft delete: the account disappears and its email can be used again. "
					+ "Nobody can delete themselves, and the last enabled Admin cannot be deleted.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Deleted (no body)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Last enabled Admin",
			content = @Content(examples = @ExampleObject(name = EX_LAST_ADMIN, ref = REF + EX_LAST_ADMIN)))
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
		userService.delete(actor, id);
		return ResponseEntity.noContent().build();
	}

}
