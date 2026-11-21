package com.ayshriv.selfhostcrm.web;

import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_PAGE;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.REF;

import com.ayshriv.selfhostcrm.config.security.UserPrincipal;
import com.ayshriv.selfhostcrm.service.UserActivityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me")
@Validated
@Tag(name = "Users")
public class UserActivityController {

	private final UserActivityService activityService;

	public UserActivityController(UserActivityService activityService) {
		this.activityService = activityService;
	}

	/** The caller's own audit entries, newest first. The user id always comes from the token, never the request. */
	@Operation(summary = "My activity",
			description = "The signed-in user's own audit entries, newest first. size is capped at 100.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "A page of activity",
			content = @Content(examples = @ExampleObject(name = EX_PAGE, ref = REF + EX_PAGE)))
	@GetMapping("/activity")
	public ResponseEntity<ApiResponse<List<UserLogResponse>>> activity(@AuthenticationPrincipal UserPrincipal principal,
			@RequestParam(defaultValue = "0") @Min(value = 0, message = "page must be 0 or greater") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = "size must be at least 1")
			@Max(value = 100, message = "size must be at most 100") int size) {
		Page<UserLogResponse> result = activityService.findOwn(principal.getId(), page, size);
		return ResponseEntity.ok(ApiResponse.success("Activity retrieved successfully", result.getContent(),
				PageMetadata.from(result)));
	}

}
