package com.ayshriv.selfhostcrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import lombok.Getter;
import lombok.Setter;

/**
 * One-time invitation to join. Only the SHA-256 of the token is stored. Never soft-deleted: it ends by
 * being accepted or revoked, so there is deliberately no {@code @SQLRestriction}. The inviter and the
 * accepted user are plain ids (not associations) because those users may be soft-deleted later.
 */
@Entity
@Table(name = "invitations")
@Getter
@Setter
public class Invitation extends BaseEntity {

	/** Stored lowercase; see {@link #setEmail(String)}. */
	@Column(name = "email", nullable = false, length = 254)
	private String email;

	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = 20)
	private Role role;

	@Column(name = "invited_by", nullable = false)
	private Long invitedBy;

	@Column(name = "token_hash", nullable = false, length = 64)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "accepted_at")
	private Instant acceptedAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "accepted_user_id")
	private Long acceptedUserId;

	public void setEmail(String email) {
		this.email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
	}

	public boolean isPending(Instant now) {
		return acceptedAt == null && revokedAt == null && expiresAt.isAfter(now);
	}

}
