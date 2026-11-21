package com.ayshriv.selfhostcrm.web;

import com.ayshriv.selfhostcrm.domain.Role;
import java.time.Instant;

/**
 * Returned once when an invitation is created. {@code acceptUrl} contains the raw token and can never be
 * retrieved again (only its SHA-256 is stored); share it with the invitee yourself.
 */
public record CreatedInvitationResponse(Long id, String email, Role role, Instant expiresAt, String acceptUrl) {
}
