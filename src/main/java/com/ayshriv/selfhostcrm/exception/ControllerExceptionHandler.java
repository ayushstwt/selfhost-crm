package com.ayshriv.selfhostcrm.exception;

import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import com.ayshriv.selfhostcrm.web.ApiError;
import com.ayshriv.selfhostcrm.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates exceptions thrown by REST controllers into the {@link ApiResponse} envelope.
 * Framework (4xx) exceptions are handled by {@link ResponseEntityExceptionHandler}; the ones raised
 * before a controller is chosen (405, 415, unknown routes) arrive through {@link WebExceptionHandler}.
 */
@RestControllerAdvice(annotations = RestController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ControllerExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ControllerExceptionHandler.class);

	@ExceptionHandler(BaseException.class)
	public ResponseEntity<ApiResponse<Void>> handleBase(BaseException ex, HttpServletRequest req) {
		ErrorMessages em = ex.getErrorMessage();
		if (em.getHttpStatus().is5xxServerError()) {
			log.error("{} on {}: {}", em.name(), req.getRequestURI(), ex.getMessage(), ex);
		}
		else {
			log.warn("{} on {}: {}", em.name(), req.getRequestURI(), ex.getMessage());
		}
		return ResponseEntity.status(em.getHttpStatus())
				.body(ApiResponse.error(em.name(), ex.getMessage(), req.getRequestURI()));
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ApiResponse<Void>> handleConstraints(ConstraintViolationException ex,
			HttpServletRequest req) {
		Optional<ErrorMessages> mapped = ex.getConstraintViolations().stream().findFirst()
				.map(ConstraintViolation::getMessageTemplate)
				.flatMap(ControllerExceptionHandler::toErrorMessage);
		ErrorMessages em = mapped.orElse(ErrorMessages.E005);
		List<ApiError> errors = null;
		if (mapped.isEmpty()) {
			errors = ex.getConstraintViolations().stream()
					.map(v -> new ApiError(leafName(v.getPropertyPath()), v.getMessage())).toList();
		}
		log.warn("{} on {}", em.name(), req.getRequestURI());
		return ResponseEntity.status(em.getHttpStatus())
				.body(ApiResponse.error(em.name(), em.getMessage(), errors, req.getRequestURI()));
	}

	/** Never swallow Spring Security exceptions: the security filter chain answers them (401/403). */
	@ExceptionHandler({ AccessDeniedException.class, AuthenticationException.class })
	public void rethrowSecurity(Exception ex) throws Exception {
		throw ex;
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest req) {
		log.error("Unexpected error on {}", req.getRequestURI(), ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse
				.error(ErrorMessages.E500.name(), ErrorMessages.E500.getMessage(), req.getRequestURI()));
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<ApiError> errors = new ArrayList<>();
		ex.getBindingResult().getFieldErrors()
				.forEach(f -> errors.add(new ApiError(f.getField(), f.getDefaultMessage())));
		ex.getBindingResult().getGlobalErrors()
				.forEach(g -> errors.add(new ApiError(g.getObjectName(), g.getDefaultMessage())));
		return validationFailed(errors, request);
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<ApiError> errors = new ArrayList<>();
		ex.getParameterValidationResults().forEach(r -> r.getResolvableErrors()
				.forEach(e -> errors.add(new ApiError(r.getMethodParameter().getParameterName(),
						e.getDefaultMessage()))));
		return validationFailed(errors, request);
	}

	/** Funnel for every framework exception: same envelope, natural status, never internals. */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		String path = path(request);
		ErrorMessages em = forStatus(statusCode);
		String message = em.getMessage();
		if (statusCode.is4xxClientError() && body instanceof ProblemDetail pd && pd.getDetail() != null) {
			message = pd.getDetail();
		}
		if (statusCode.is5xxServerError()) {
			log.error("{} on {}", statusCode, path, ex);
		}
		else {
			log.warn("{} on {}: {}", statusCode.value(), path, ex.getClass().getSimpleName());
		}
		return ResponseEntity.status(statusCode).headers(headers)
				.body(ApiResponse.error(em.name(), message, path));
	}

	private ResponseEntity<Object> validationFailed(List<ApiError> errors, WebRequest request) {
		String path = path(request);
		log.warn("{} on {}", ErrorMessages.E005.name(), path);
		return ResponseEntity.status(ErrorMessages.E005.getHttpStatus()).body(ApiResponse
				.error(ErrorMessages.E005.name(), ErrorMessages.E005.getMessage(), errors, path));
	}

	/** Last node of a constraint path, so internal method names (e.g. "activity.size") are not exposed. */
	private static String leafName(jakarta.validation.Path path) {
		String name = null;
		for (jakarta.validation.Path.Node node : path) {
			name = node.getName();
		}
		return name;
	}

	private static String path(WebRequest request) {
		return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : null;
	}

	static ErrorMessages forStatus(HttpStatusCode status) {
		return switch (status.value()) {
			case 404 -> ErrorMessages.E001;
			case 405 -> ErrorMessages.E405;
			case 415 -> ErrorMessages.E415;
			default -> status.is5xxServerError() ? ErrorMessages.E500 : ErrorMessages.E400;
		};
	}

	private static Optional<ErrorMessages> toErrorMessage(String name) {
		try {
			return Optional.of(ErrorMessages.valueOf(name));
		}
		catch (IllegalArgumentException | NullPointerException e) {
			return Optional.empty();
		}
	}

}
