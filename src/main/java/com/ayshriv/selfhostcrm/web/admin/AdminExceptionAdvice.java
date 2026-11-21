package com.ayshriv.selfhostcrm.web.admin;

import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import com.ayshriv.selfhostcrm.exception.BaseException;
import com.ayshriv.selfhostcrm.exception.WebExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

/**
 * Errors of the admin pages. An HTMX request that fails gets a small message fragment swapped into the
 * {@code #flash} area (status code kept); a normal page request gets the friendly error page.
 */
@ControllerAdvice(basePackageClasses = AdminExceptionAdvice.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminExceptionAdvice {

	private static final Logger log = LoggerFactory.getLogger(AdminExceptionAdvice.class);

	private final WebExceptionHandler pages;

	public AdminExceptionAdvice(WebExceptionHandler pages) {
		this.pages = pages;
	}

	@ExceptionHandler(BaseException.class)
	public ModelAndView handleBase(BaseException ex, HttpServletRequest request, HttpServletResponse response) {
		if (!HtmxSupport.isPartial(request) || ex.getErrorMessage().getHttpStatus().is5xxServerError()) {
			return pages.handleBase(ex, request, response);
		}
		log.warn("{} on {}: {}", ex.getErrorMessage().name(), request.getRequestURI(), ex.getMessage());
		return flash(ex.getErrorMessage().getHttpStatus(), ex.getMessage(), response);
	}

	@ExceptionHandler(AccessDeniedException.class)
	public ModelAndView handleDenied(AccessDeniedException ex, HttpServletRequest request,
			HttpServletResponse response) {
		log.warn("403 on {}", request.getRequestURI());
		if (!HtmxSupport.isPartial(request)) {
			response.setStatus(HttpStatus.FORBIDDEN.value());
			ModelAndView page = new ModelAndView("error/error");
			page.addObject("status", 403);
			page.addObject("message", ErrorMessages.E004.getMessage());
			return page;
		}
		return flash(HttpStatus.FORBIDDEN, ErrorMessages.E004.getMessage(), response);
	}

	private static ModelAndView flash(HttpStatus status, String message, HttpServletResponse response) {
		response.setStatus(status.value());
		response.setHeader("HX-Retarget", "#flash");
		response.setHeader("HX-Reswap", "innerHTML");
		ModelAndView mav = new ModelAndView(HtmxSupport.FLASH);
		mav.addObject("message", message);
		return mav;
	}

}
