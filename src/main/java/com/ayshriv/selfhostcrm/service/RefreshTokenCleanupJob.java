package com.ayshriv.selfhostcrm.service;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically deletes expired refresh tokens. Enabled with app.security.refresh-token-cleanup.enabled. */
@Component
@ConditionalOnProperty(name = "app.security.refresh-token-cleanup.enabled", havingValue = "true")
public class RefreshTokenCleanupJob {

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

	private final RefreshTokenService refreshTokens;

	public RefreshTokenCleanupJob(RefreshTokenService refreshTokens) {
		this.refreshTokens = refreshTokens;
	}

	@Scheduled(fixedDelayString = "${app.security.refresh-token-cleanup.interval:1h}")
	public void cleanUp() {
		int removed = refreshTokens.deleteExpired(Instant.now());
		log.info("Removed {} expired refresh tokens", removed);
	}

}
