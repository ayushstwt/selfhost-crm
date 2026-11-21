package com.ayshriv.selfhostcrm.web;

import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_TEAM;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_TEAM_DENIED;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_TEAM_MEMBER;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_TEAM_MEMBER_PAGE;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_TEAM_NAME_TAKEN;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_TEAM_NOT_FOUND;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_TEAM_PAGE;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.REF;

import com.ayshriv.selfhostcrm.config.security.UserPrincipal;
import com.ayshriv.selfhostcrm.service.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Teams and team membership. Thin: permissions and team rules are enforced in {@code TeamService}. */
@RestController
@RequestMapping("/api/teams")
@Validated
@Tag(name = "Teams")
public class TeamController {

	private final TeamService teamService;

	public TeamController(TeamService teamService) {
		this.teamService = teamService;
	}

	@Operation(summary = "List teams",
			description = "Admin: all teams. Everyone else: only the teams they belong to. Ordered by name. "
					+ "Filter: q (name contains, case-insensitive). size max 100.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "A page of teams",
			content = @Content(examples = @ExampleObject(name = EX_TEAM_PAGE, ref = REF + EX_TEAM_PAGE)))
	@GetMapping
	public ResponseEntity<ApiResponse<List<TeamResponse>>> list(@AuthenticationPrincipal UserPrincipal actor,
			@RequestParam(name = "q", required = false) String search,
			@RequestParam(defaultValue = "0") @Min(value = 0, message = "page must be 0 or greater") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = "size must be at least 1")
			@Max(value = 100, message = "size must be at most 100") int size) {
		Page<TeamResponse> result = teamService.list(actor, search, page, size);
		return ResponseEntity.ok(ApiResponse.success("Teams retrieved successfully", result.getContent(),
				PageMetadata.from(result)));
	}

	@Operation(summary = "Get a team",
			description = "With the number of members. Admin: any team (404 if it does not exist). Everyone else: "
					+ "only teams they belong to; any other team id, existing or not, answers 403.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The team",
			content = @Content(examples = @ExampleObject(name = EX_TEAM, ref = REF + EX_TEAM)))
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not your team",
			content = @Content(examples = @ExampleObject(name = EX_TEAM_DENIED, ref = REF + EX_TEAM_DENIED)))
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Unknown team (Admin only)",
			content = @Content(examples = @ExampleObject(name = EX_TEAM_NOT_FOUND, ref = REF + EX_TEAM_NOT_FOUND)))
	@GetMapping("/{id}")
	public ResponseEntity<ApiResponse<TeamResponse>> get(@AuthenticationPrincipal UserPrincipal actor,
			@PathVariable Long id) {
		return ResponseEntity.ok(ApiResponse.success("Team retrieved successfully", teamService.get(actor, id)));
	}

	@Operation(summary = "Create a team", description = "Admin only. Names are unique, ignoring case.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Created; Location points at the team",
			content = @Content(examples = @ExampleObject(name = EX_TEAM, ref = REF + EX_TEAM)))
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Name already in use",
			content = @Content(examples = @ExampleObject(name = EX_TEAM_NAME_TAKEN, ref = REF + EX_TEAM_NAME_TAKEN)))
	@PostMapping
	public ResponseEntity<ApiResponse<TeamResponse>> create(@AuthenticationPrincipal UserPrincipal actor,
			@Valid @RequestBody CreateTeamRequest request) {
		TeamResponse created = teamService.create(actor, request.name(), request.description());
		URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id())
				.toUri();
		return ResponseEntity.created(location).body(ApiResponse.success("Team created successfully", created));
	}

	@Operation(summary = "Rename a team or change its description", description = "Admin only. An empty description clears it.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The updated team",
			content = @Content(examples = @ExampleObject(name = EX_TEAM, ref = REF + EX_TEAM)))
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Name already in use",
			content = @Content(examples = @ExampleObject(name = EX_TEAM_NAME_TAKEN, ref = REF + EX_TEAM_NAME_TAKEN)))
	@PatchMapping("/{id}")
	public ResponseEntity<ApiResponse<TeamResponse>> update(@AuthenticationPrincipal UserPrincipal actor,
			@PathVariable Long id, @Valid @RequestBody UpdateTeamRequest request) {
		return ResponseEntity.ok(ApiResponse.success("Team updated successfully",
				teamService.update(actor, id, request.name(), request.description())));
	}

	@Operation(summary = "Delete a team",
			description = "Admin only. Soft delete: the team and its memberships disappear and the name can be used "
					+ "again. Team leads lose their management rights at once.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Deleted (no body)")
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
		teamService.delete(actor, id);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "List the members of a team",
			description = "Admin: any team. Others: teams they belong to. Disabled and deleted users are not listed. "
					+ "email is only filled for callers who may view users. size max 100.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "A page of members",
			content = @Content(examples = @ExampleObject(name = EX_TEAM_MEMBER_PAGE, ref = REF + EX_TEAM_MEMBER_PAGE)))
	@GetMapping("/{id}/members")
	public ResponseEntity<ApiResponse<List<TeamMemberResponse>>> members(@AuthenticationPrincipal UserPrincipal actor,
			@PathVariable Long id,
			@RequestParam(defaultValue = "0") @Min(value = 0, message = "page must be 0 or greater") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = "size must be at least 1")
			@Max(value = 100, message = "size must be at most 100") int size) {
		Page<TeamMemberResponse> result = teamService.listMembers(actor, id, page, size);
		return ResponseEntity.ok(ApiResponse.success("Team members retrieved successfully", result.getContent(),
				PageMetadata.from(result)));
	}

	@Operation(summary = "Get one member of a team", description = "Same visibility as the member list. 404 if the user is not a member.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The member",
			content = @Content(examples = @ExampleObject(name = EX_TEAM_MEMBER, ref = REF + EX_TEAM_MEMBER)))
	@GetMapping("/{id}/members/{userId}")
	public ResponseEntity<ApiResponse<TeamMemberResponse>> member(@AuthenticationPrincipal UserPrincipal actor,
			@PathVariable Long id, @PathVariable Long userId) {
		return ResponseEntity.ok(ApiResponse.success("Team member retrieved successfully",
				teamService.getMember(actor, id, userId)));
	}

	@Operation(summary = "Add a user to a team or change their team role",
			description = "Idempotent. 201 when the user was added, 200 when the role was set on an existing member. "
					+ "Admin: any user, any team. Manager: only in teams they lead, and only users whose account role is "
					+ "MEMBER. A disabled user cannot be added (409). Demoting a lead cuts off their existing tokens.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Added; Location points at the member",
			content = @Content(examples = @ExampleObject(name = EX_TEAM_MEMBER, ref = REF + EX_TEAM_MEMBER)))
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Team role set",
			content = @Content(examples = @ExampleObject(name = EX_TEAM_MEMBER, ref = REF + EX_TEAM_MEMBER)))
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not allowed on this team",
			content = @Content(examples = @ExampleObject(name = EX_TEAM_DENIED, ref = REF + EX_TEAM_DENIED)))
	@PutMapping("/{id}/members/{userId}")
	public ResponseEntity<ApiResponse<TeamMemberResponse>> setMember(@AuthenticationPrincipal UserPrincipal actor,
			@PathVariable Long id, @PathVariable Long userId, @Valid @RequestBody SetTeamMemberRequest request) {
		TeamService.MemberResult result = teamService.setMember(actor, id, userId, request.teamRole());
		if (result.created()) {
			URI location = ServletUriComponentsBuilder.fromCurrentRequest().build().toUri();
			return ResponseEntity.created(location)
					.body(ApiResponse.success("User added to the team", result.member()));
		}
		return ResponseEntity.ok(ApiResponse.success("Team role saved", result.member()));
	}

	@Operation(summary = "Remove a user from a team",
			description = "Admin: any user, any team. Manager: only in teams they lead, only users whose account role is MEMBER. "
					+ "Removing a lead cuts off their existing tokens.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Removed (no body)")
	@DeleteMapping("/{id}/members/{userId}")
	public ResponseEntity<Void> removeMember(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id,
			@PathVariable Long userId) {
		teamService.removeMember(actor, id, userId);
		return ResponseEntity.noContent().build();
	}

}
