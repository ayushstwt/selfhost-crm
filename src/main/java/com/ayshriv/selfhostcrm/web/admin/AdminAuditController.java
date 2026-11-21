package com.ayshriv.selfhostcrm.web.admin;

import com.ayshriv.selfhostcrm.config.security.UserPrincipal;
import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import com.ayshriv.selfhostcrm.domain.AuditTargetType;
import com.ayshriv.selfhostcrm.domain.UserAction;
import com.ayshriv.selfhostcrm.exception.ServiceException;
import com.ayshriv.selfhostcrm.service.AuditQueryService;
import com.ayshriv.selfhostcrm.web.AuditLogResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Admin audit page (ADMIN only; {@link AuditQueryService} enforces {@code AUDIT_VIEW_ALL}). */
@Controller
@RequestMapping("/admin/audit")
public class AdminAuditController {

	private static final int PAGE_SIZE = 25;

	private final AuditQueryService audit;

	public AdminAuditController(AuditQueryService audit) {
		this.audit = audit;
	}

	@GetMapping
	public String list(@AuthenticationPrincipal UserPrincipal actor, @RequestParam(required = false) String actorId,
			@RequestParam(required = false) String action, @RequestParam(required = false) String targetType,
			@RequestParam(required = false) String from, @RequestParam(required = false) String to,
			@RequestParam(required = false) String page, Model model, HttpServletRequest request) {
		Long actorFilter = parseLong(actorId);
		UserAction actionFilter = HtmxSupport.parseEnum(UserAction.class, action);
		AuditTargetType typeFilter = HtmxSupport.parseEnum(AuditTargetType.class, targetType);
		LocalDate fromDate = parseDate(from);
		LocalDate toDate = parseDate(to);
		Instant fromInstant = fromDate == null ? null : fromDate.atStartOfDay(ZoneOffset.UTC).toInstant();
		// inclusive end of the chosen day
		Instant toInstant = toDate == null ? null
				: toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1);
		if (fromInstant != null && toInstant != null && fromInstant.isAfter(toInstant)) {
			throw new ServiceException(ErrorMessages.E005);
		}
		Page<AuditLogResponse> result = audit.search(actorFilter, actionFilter, typeFilter, null, fromInstant,
				toInstant, HtmxSupport.intParam(page, 0), PAGE_SIZE);
		model.addAttribute("entries", result.getContent());
		model.addAttribute("page", result);
		model.addAttribute("actorId", actorFilter == null ? "" : actorFilter.toString());
		model.addAttribute("actionFilter", actionFilter == null ? "" : actionFilter.name());
		model.addAttribute("typeFilter", typeFilter == null ? "" : typeFilter.name());
		model.addAttribute("from", fromDate == null ? "" : fromDate.toString());
		model.addAttribute("to", toDate == null ? "" : toDate.toString());
		model.addAttribute("actions", UserAction.values());
		model.addAttribute("targetTypes", AuditTargetType.values());
		return HtmxSupport.isPartial(request) ? "admin/audit :: table" : "admin/audit";
	}

	private static Long parseLong(String value) {
		try {
			return value == null || value.isBlank() ? null : Long.valueOf(value.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static LocalDate parseDate(String value) {
		try {
			return value == null || value.isBlank() ? null : LocalDate.parse(value.trim());
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

}
