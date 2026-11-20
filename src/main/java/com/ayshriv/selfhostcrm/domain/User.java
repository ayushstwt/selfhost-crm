package com.ayshriv.selfhostcrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "users")
@SQLRestriction("is_deleted = false")
@Getter
@Setter
public class User extends BaseEntity {

	/** Stored lowercase; see {@link #setEmail(String)}. */
	@Column(name = "email", nullable = false, unique = true, length = 254)
	private String email;

	@Column(name = "display_name", nullable = false, length = 100)
	private String displayName;

	@Column(name = "password_hash", nullable = false, length = 100)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = 20)
	private Role role;

	@Column(name = "enabled", nullable = false)
	private boolean enabled = true;

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	@Column(name = "failed_login_count", nullable = false)
	private int failedLoginCount;

	@Column(name = "locked_until")
	private Instant lockedUntil;

	/** Set on password change; web sessions authenticated before it are invalidated. */
	@Column(name = "credentials_changed_at")
	private Instant credentialsChangedAt;

	/**
	 * Set when role, enabled or deleted state changes. Access tokens issued before it and web sessions
	 * authenticated before it are rejected. JWT {@code iat} has whole-second precision, so changes store the
	 * <em>next</em> whole second (see {@code AccessRevocationService}) and new users start at the current
	 * whole second: no token minted in the same second as a change can survive it.
	 */
	@Column(name = "access_changed_at", nullable = false)
	private Instant accessChangedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);

	public User() {
	}

	public User(Long id) {
		super(id);
	}

	public void setEmail(String email) {
		this.email = email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
	}

}
