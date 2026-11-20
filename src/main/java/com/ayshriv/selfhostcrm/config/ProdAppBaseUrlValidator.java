package com.ayshriv.selfhostcrm.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Fails startup in the prod profile unless APP_BASE_URL is an absolute http(s) URL. */
@Component
@Profile("prod")
public class ProdAppBaseUrlValidator {

	public ProdAppBaseUrlValidator(AppProperties properties) {
		validate(properties.baseUrl());
	}

	static void validate(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("APP_BASE_URL is not set. Set it to the public address of this "
					+ "install, for example https://crm.example.com (it is used to build invitation links).");
		}
		URI uri;
		try {
			uri = new URI(value.trim());
		}
		catch (URISyntaxException ex) {
			throw new IllegalStateException("APP_BASE_URL is not a valid URL.");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
			throw new IllegalStateException("APP_BASE_URL must be an absolute http or https URL, for example "
					+ "https://crm.example.com.");
		}
		if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
			throw new IllegalStateException("APP_BASE_URL must not contain credentials, a query or a fragment.");
		}
	}

}
