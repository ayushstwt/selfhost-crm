package com.ayshriv.selfhostcrm.config.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Login throttling: after {@code maxAttempts} failures an account is locked for {@code duration}. */
@ConfigurationProperties("app.security.lockout")
public record LockoutProperties(@DefaultValue("5") int maxAttempts, @DefaultValue("15m") Duration duration) {
}
