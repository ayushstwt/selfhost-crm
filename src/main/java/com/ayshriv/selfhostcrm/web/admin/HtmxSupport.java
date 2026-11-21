package com.ayshriv.selfhostcrm.web.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;

/** Small helpers shared by the admin page controllers. */
final class HtmxSupport {

	static final String EMPTY = "fragments/layout :: empty";

	static final String FLASH = "fragments/layout :: flash";

	private HtmxSupport() {
	}

	/** An HTMX request that wants a fragment (boosted navigation wants a full page). */
	static boolean isPartial(HttpServletRequest request) {
		return "true".equals(request.getHeader("HX-Request")) && request.getHeader("HX-Boosted") == null;
	}

	/** HTMX: client-side redirect header; plain browser post: ordinary redirect. */
	static String redirect(HttpServletRequest request, HttpServletResponse response, String path) {
		if (isPartial(request)) {
			response.setHeader("HX-Redirect", request.getContextPath() + path);
			return EMPTY;
		}
		return "redirect:" + path;
	}

	static int intParam(String value, int fallback) {
		try {
			return value == null || value.isBlank() ? fallback : Math.max(0, Integer.parseInt(value.trim()));
		}
		catch (NumberFormatException ex) {
			return fallback;
		}
	}

	/** Lenient enum filter: blank or unknown values mean "no filter" (never a 500). */
	static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	/** {@code true}/{@code false}; anything else means no filter. */
	static Boolean parseBoolean(String value) {
		if ("true".equalsIgnoreCase(value)) {
			return Boolean.TRUE;
		}
		return "false".equalsIgnoreCase(value) ? Boolean.FALSE : null;
	}

}
