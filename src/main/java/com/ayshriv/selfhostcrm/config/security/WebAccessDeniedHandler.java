package com.ayshriv.selfhostcrm.config.security;

import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.View;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/**
 * Web chain 403 (URL rules, method security and CSRF failures): renders the friendly error page (status and
 * message only, no stack trace) directly with the existing {@code error/error} template. No forward and no
 * container error page are involved. The API chain has its own handler.
 */
public class WebAccessDeniedHandler implements AccessDeniedHandler {

	private final ThymeleafViewResolver viewResolver;

	public WebAccessDeniedHandler(ThymeleafViewResolver viewResolver) {
		this.viewResolver = viewResolver;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException, ServletException {
		if (response.isCommitted()) {
			return;
		}
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		try {
			View view = viewResolver.resolveViewName("error/error", request.getLocale());
			view.render(Map.of("status", 403, "message", ErrorMessages.E004.getMessage()), request, response);
		}
		catch (IOException | ServletException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new ServletException("Could not render the 403 page", ex);
		}
	}

}
