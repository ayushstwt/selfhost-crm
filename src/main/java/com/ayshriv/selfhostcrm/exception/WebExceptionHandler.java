package com.ayshriv.selfhostcrm.exception;

import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Error handling for Thymeleaf (non-REST) requests: friendly HTML pages, never stack traces.
 * It also receives framework exceptions raised before any controller is selected (405, 415,
 * unknown routes); for {@code /api/**} those are delegated to {@link ControllerExceptionHandler}
 * so they keep the {@code ApiResponse} shape.
 */
@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class WebExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(WebExceptionHandler.class);

	private final ControllerExceptionHandler apiHandler;

	public WebExceptionHandler(ControllerExceptionHandler apiHandler) {
		this.apiHandler = apiHandler;
	}

	@ExceptionHandler({ HttpRequestMethodNotSupportedException.class, HttpMediaTypeNotSupportedException.class,
			HttpMediaTypeNotAcceptableException.class, NoResourceFoundException.class,
			NoHandlerFoundException.class })
	public Object handleFramework(Exception ex, HttpServletRequest request, HttpServletResponse response,
			WebRequest webRequest) throws Exception {
		if (isApi(request)) {
			return apiHandler.handleException(ex, webRequest);
		}
		HttpStatusCode status = ex instanceof ErrorResponse er ? er.getStatusCode() : HttpStatus.BAD_REQUEST;
		log.warn("{} on {}: {}", status.value(), request.getRequestURI(), ex.getClass().getSimpleName());
		return page(status, ControllerExceptionHandler.forStatus(status).getMessage(), response);
	}

	@ExceptionHandler(BaseException.class)
	public ModelAndView handleBase(BaseException ex, HttpServletRequest request, HttpServletResponse response) {
		ErrorMessages em = ex.getErrorMessage();
		if (em.getHttpStatus().is5xxServerError()) {
			log.error("{} on {}", em.name(), request.getRequestURI(), ex);
		}
		else {
			log.warn("{} on {}: {}", em.name(), request.getRequestURI(), ex.getMessage());
		}
		return page(em.getHttpStatus(), ex.getMessage(), response);
	}

	/** Spring Security answers these through its filter chain. */
	@ExceptionHandler({ AccessDeniedException.class, AuthenticationException.class })
	public void rethrowSecurity(Exception ex) throws Exception {
		throw ex;
	}

	@ExceptionHandler(Exception.class)
	public ModelAndView handleUnexpected(Exception ex, HttpServletRequest request, HttpServletResponse response) {
		log.error("Unexpected error on {}", request.getRequestURI(), ex);
		return page(HttpStatus.INTERNAL_SERVER_ERROR, ErrorMessages.E500.getMessage(), response);
	}

	private static boolean isApi(HttpServletRequest request) {
		String uri = request.getRequestURI();
		return uri != null && (uri.equals("/api") || uri.startsWith("/api/"));
	}

	private static ModelAndView page(HttpStatusCode status, String message, HttpServletResponse response) {
		response.setStatus(status.value());
		ModelAndView mav = new ModelAndView("error/error");
		mav.addObject("status", status.value());
		mav.addObject("message", message);
		return mav;
	}

}
