package com.ayshriv.selfhostcrm.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * General application settings. {@code baseUrl} (env APP_BASE_URL) is the public address of this install,
 * used to build invitation links. Prod refuses to start without a valid absolute http(s) URL.
 */
@ConfigurationProperties("app")
public record AppProperties(@DefaultValue("") String baseUrl, @DefaultValue Invitations invitations) {

	public record Invitations(@DefaultValue("7d") Duration ttl) {
	}

	/** Base URL without a trailing slash. */
	public String normalizedBaseUrl() {
		String url = baseUrl == null ? "" : baseUrl.trim();
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		return url;
	}

}
