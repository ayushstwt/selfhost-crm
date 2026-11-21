package com.ayshriv.selfhostcrm.web;

import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_ACCEPTED_USER;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_INVALID_INVITATION;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_INVITATION;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_INVITATION_CREATED;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_INVITATION_PAGE;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.REF;

import com.ayshriv.selfhostcrm.config.security.UserPrincipal;
import com.ayshriv.selfhostcrm.service.InvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** One-time invitation links. Thin: rules live in {@code InvitationService}. */
@RestController
@RequestMapping("/api/invitations")
@Validated
@Tag(name = "Invitations")
public class InvitationController {

	private final InvitationService invitationService;

	public InvitationController(InvitationService invitationService) {
		this.invitationService = invitationService;
	}

	@Operation(summary = "Create an invitation",
			description = "Admin: any role. Manager: Member only. The response contains the one-time acceptUrl: it is "
					+ "shown only now (only a hash is stored) and must be sent to the invitee by you. A new invitation "
					+ "for the same email revokes the previous pending one. 409 if an account with that email exists.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Created; Location points at the invitation",
			content = @Content(examples = @ExampleObject(name = EX_INVITATION_CREATED, ref = REF + EX_INVITATION_CREATED)))
	@PostMapping
	public ResponseEntity<ApiResponse<CreatedInvitationResponse>> create(
			@AuthenticationPrincipal UserPrincipal actor, @Valid @RequestBody CreateInvitationRequest request) {
		CreatedInvitationResponse created = invitationService.create(actor, request.email(), request.role());
		URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id())
				.toUri();
		return ResponseEntity.created(location)
				.body(ApiResponse.success("Invitation created. Share the link now: it cannot be shown again.", created));
	}

	@Operation(summary = "List pending invitations",
			description = "Newest first. Admin sees all; Manager sees only their own. size max 100.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "A page of pending invitations",
			content = @Content(examples = @ExampleObject(name = EX_INVITATION_PAGE, ref = REF + EX_INVITATION_PAGE)))
	@GetMapping
	public ResponseEntity<ApiResponse<List<InvitationResponse>>> list(@AuthenticationPrincipal UserPrincipal actor,
			@RequestParam(defaultValue = "0") @Min(value = 0, message = "page must be 0 or greater") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = "size must be at least 1")
			@Max(value = 100, message = "size must be at most 100") int size) {
		Page<InvitationResponse> result = invitationService.listPending(actor, page, size);
		return ResponseEntity.ok(ApiResponse.success("Invitations retrieved successfully", result.getContent(),
				PageMetadata.from(result)));
	}

	@Operation(summary = "Get one pending invitation",
			description = "The target of the Location header returned on create. Admin: any pending invitation. "
					+ "Manager: only their own (403 otherwise). Never contains the token. 404 once it is no longer pending.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The invitation",
			content = @Content(examples = @ExampleObject(name = EX_INVITATION, ref = REF + EX_INVITATION)))
	@GetMapping("/{id}")
	public ResponseEntity<ApiResponse<InvitationResponse>> get(@AuthenticationPrincipal UserPrincipal actor,
			@PathVariable Long id) {
		return ResponseEntity.ok(ApiResponse.success("Invitation retrieved successfully", invitationService.get(actor, id)));
	}

	@Operation(summary = "Revoke an invitation",
			description = "Admin: any pending invitation. Manager: only their own.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Revoked (no body)")
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> revoke(@AuthenticationPrincipal UserPrincipal actor, @PathVariable Long id) {
		invitationService.revoke(actor, id);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "Accept an invitation",
			description = "Public: the one-time token is the credential. Creates the account with the invited role. "
					+ "Invalid, expired, used and revoked tokens all return the same 400.")
	@SecurityRequirements
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Account created",
			content = @Content(examples = @ExampleObject(name = EX_ACCEPTED_USER, ref = REF + EX_ACCEPTED_USER)))
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid or expired link",
			content = @Content(examples = @ExampleObject(name = EX_INVALID_INVITATION, ref = REF + EX_INVALID_INVITATION)))
	@PostMapping("/accept")
	public ResponseEntity<ApiResponse<UserResponse>> accept(@Valid @RequestBody AcceptInvitationRequest request) {
		UserResponse user = invitationService.accept(request.token(), request.displayName(), request.password());
		URI location = ServletUriComponentsBuilder.fromCurrentContextPath().path("/api/users/{id}")
				.buildAndExpand(user.id()).toUri();
		return ResponseEntity.created(location).body(ApiResponse.success("Account created. You can sign in now.", user));
	}

}
