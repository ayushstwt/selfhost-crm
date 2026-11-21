package com.ayshriv.selfhostcrm.web;

import static com.ayshriv.selfhostcrm.config.OpenApiConfig.EX_AUDIT_PAGE;
import static com.ayshriv.selfhostcrm.config.OpenApiConfig.REF;

import com.ayshriv.selfhostcrm.domain.AuditTargetType;
import com.ayshriv.selfhostcrm.domain.UserAction;
import com.ayshriv.selfhostcrm.service.AuditQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
@Validated
@Tag(name = "Audit")
public class AuditController {

	private final AuditQueryService auditService;

	public AuditController(AuditQueryService auditService) {
		this.auditService = auditService;
	}

	@Operation(summary = "Audit log (all users)",
			description = "Admin only. Newest first. Filters: actorId, action, targetType, targetId, from and to "
					+ "(ISO-8601 instants, inclusive). size max 100. Never contains tokens, passwords or request bodies.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "A page of audit entries",
			content = @Content(examples = @ExampleObject(name = EX_AUDIT_PAGE, ref = REF + EX_AUDIT_PAGE)))
	@GetMapping
	public ResponseEntity<ApiResponse<List<AuditLogResponse>>> search(
			@RequestParam(required = false) Long actorId,
			@RequestParam(required = false) UserAction action,
			@RequestParam(required = false) AuditTargetType targetType,
			@RequestParam(required = false) Long targetId,
			@RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to,
			@RequestParam(defaultValue = "0") @Min(value = 0, message = "page must be 0 or greater") int page,
			@RequestParam(defaultValue = "20") @Min(value = 1, message = "size must be at least 1")
			@Max(value = 100, message = "size must be at most 100") int size) {
		Page<AuditLogResponse> result = auditService.search(actorId, action, targetType, targetId, from, to, page,
				size);
		return ResponseEntity.ok(ApiResponse.success("Audit log retrieved successfully", result.getContent(),
				PageMetadata.from(result)));
	}

}
