package com.ayshriv.selfhostcrm.service;

import com.ayshriv.selfhostcrm.config.AppProperties;
import com.ayshriv.selfhostcrm.config.security.UserAccessPolicy;
import com.ayshriv.selfhostcrm.config.security.UserPrincipal;
import com.ayshriv.selfhostcrm.constants.ErrorMessages;
import com.ayshriv.selfhostcrm.domain.AuditTargetType;
import com.ayshriv.selfhostcrm.domain.Invitation;
import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import com.ayshriv.selfhostcrm.domain.UserAction;
import com.ayshriv.selfhostcrm.exception.ResourceAlreadyExistException;
import com.ayshriv.selfhostcrm.exception.ResourceNotFoundException;
import com.ayshriv.selfhostcrm.exception.ServiceException;
import com.ayshriv.selfhostcrm.repository.InvitationRepository;
import com.ayshriv.selfhostcrm.repository.UserRepository;
import com.ayshriv.selfhostcrm.web.CreatedInvitationResponse;
import com.ayshriv.selfhostcrm.web.InvitationResponse;
import com.ayshriv.selfhostcrm.web.UserResponse;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time invitation links (no email is sent: the link is shown to the inviter once). The raw token is
 * 256 random bits, URL-safe; only its SHA-256 is stored. Accepting is public and answers every invalid,
 * expired, used or revoked token with the same error.
 */
@Service
public class InvitationService {

	private final InvitationRepository invitations;

	private final UserRepository users;

	private final UserAccessPolicy policy;

	private final UserLogService userLog;

	private final PasswordEncoder passwordEncoder;

	private final JdbcTemplate jdbc;

	private final AppProperties properties;

	public InvitationService(InvitationRepository invitations, UserRepository users, UserAccessPolicy policy,
			UserLogService userLog, PasswordEncoder passwordEncoder, JdbcTemplate jdbc, AppProperties properties) {
		this.invitations = invitations;
		this.users = users;
		this.policy = policy;
		this.userLog = userLog;
		this.passwordEncoder = passwordEncoder;
		this.jdbc = jdbc;
		this.properties = properties;
	}

	/**
	 * Creates an invitation and returns the one-time accept URL (the only time the raw token exists). A
	 * previous open invitation for the same email is revoked first; a Manager cannot replace a live
	 * invitation created by someone else.
	 */
	@PreAuthorize("hasAuthority('USER_INVITE')")
	@Transactional
	public CreatedInvitationResponse create(UserPrincipal actor, String rawEmail, Role role) {
		policy.assertCanInvite(actor, role);
		String email = rawEmail.trim().toLowerCase(Locale.ROOT);
		// Serializes concurrent invitations for the same email (the partial unique index is the backstop).
		jdbc.query("select pg_advisory_xact_lock(hashtext(?))", rs -> {
		}, email);

		if (users.existsByEmail(email)) {
			throw new ResourceAlreadyExistException(ErrorMessages.E002);
		}
		Instant now = Instant.now();
		invitations.findOpenForUpdateByEmail(email).ifPresent(previous -> {
			boolean othersLivePending = previous.isPending(now) && !previous.getInvitedBy().equals(actor.getId());
			if (othersLivePending && actor.getRole() != Role.ADMIN) {
				throw new ServiceException(ErrorMessages.E017);
			}
			previous.setRevokedAt(now);
			invitations.saveAndFlush(previous); // frees the unique pending slot before the insert below
			userLog.recordAction(actor.getId(), UserAction.INVITATION_REVOKED, AuditTargetType.INVITATION,
					previous.getId());
		});

		String rawToken = TokenHasher.newToken();
		Invitation invitation = new Invitation();
		invitation.setEmail(email);
		invitation.setRole(role);
		invitation.setInvitedBy(actor.getId());
		invitation.setTokenHash(TokenHasher.hash(rawToken));
		invitation.setExpiresAt(now.plus(properties.invitations().ttl()));
		invitation = invitations.saveAndFlush(invitation);
		userLog.recordAction(actor.getId(), UserAction.INVITATION_CREATED, AuditTargetType.INVITATION,
				invitation.getId());
		return new CreatedInvitationResponse(invitation.getId(), email, role, invitation.getExpiresAt(),
				properties.normalizedBaseUrl() + "/invite/" + rawToken);
	}

	/** Pending invitations, newest first. Admin: all. Manager: only their own. */
	@PreAuthorize("hasAuthority('INVITATION_MANAGE')")
	@Transactional(readOnly = true)
	public Page<InvitationResponse> listPending(UserPrincipal actor, int page, int size) {
		PageRequest pageable = PageRequest.of(page, size);
		Instant now = Instant.now();
		return actor.getRole() == Role.ADMIN ? invitations.findPending(now, pageable)
				: invitations.findPendingByInviter(now, actor.getId(), pageable);
	}

	/** One pending invitation, same visibility as the list: Admin any, Manager only their own. */
	@PreAuthorize("hasAuthority('INVITATION_MANAGE')")
	@Transactional(readOnly = true)
	public InvitationResponse get(UserPrincipal actor, Long id) {
		InvitationResponse invitation = invitations.findPendingById(Instant.now(), id)
				.orElseThrow(() -> new ResourceNotFoundException(ErrorMessages.E001));
		policy.assertCanManageInvitation(actor, invitation.invitedById());
		return invitation;
	}

	@PreAuthorize("hasAuthority('INVITATION_MANAGE')")
	@Transactional
	public void revoke(UserPrincipal actor, Long id) {
		Invitation invitation = invitations.findForUpdateById(id)
				.filter(i -> i.isPending(Instant.now()))
				.orElseThrow(() -> new ResourceNotFoundException(ErrorMessages.E001));
		policy.assertCanManageInvitation(actor, invitation.getInvitedBy());
		invitation.setRevokedAt(Instant.now());
		userLog.recordAction(actor.getId(), UserAction.INVITATION_REVOKED, AuditTargetType.INVITATION, id);
	}

	/** Public. For the web page: is this link still usable? No side effects. */
	@Transactional(readOnly = true)
	public boolean isPending(String rawToken) {
		return rawToken != null && !rawToken.isBlank()
				&& invitations.existsPendingByTokenHash(TokenHasher.hash(rawToken), Instant.now());
	}

	/** The email an invitation was issued for (for the accept page), or empty when the link is not usable. */
	@Transactional(readOnly = true)
	public Optional<String> pendingEmail(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			return Optional.empty();
		}
		return invitations.findByTokenHash(TokenHasher.hash(rawToken)).filter(i -> i.isPending(Instant.now()))
				.map(Invitation::getEmail);
	}

	/**
	 * Public. Creates the user with the invited role and marks the link used. Every invalid case (unknown,
	 * expired, used, revoked, email taken meanwhile) is the same {@code E016}.
	 */
	@Transactional
	public UserResponse accept(String rawToken, String displayName, String rawPassword) {
		if (rawToken == null || rawToken.isBlank()) {
			throw new ServiceException(ErrorMessages.E016);
		}
		Instant now = Instant.now();
		Invitation invitation = invitations.findForUpdateByTokenHash(TokenHasher.hash(rawToken))
				.filter(i -> i.isPending(now))
				.orElseThrow(() -> new ServiceException(ErrorMessages.E016));
		PasswordPolicy.check(invitation.getEmail(), rawPassword);
		if (users.existsByEmail(invitation.getEmail())) {
			throw new ServiceException(ErrorMessages.E016);
		}

		User user = new User();
		user.setEmail(invitation.getEmail());
		user.setDisplayName(displayName.trim());
		user.setPasswordHash(passwordEncoder.encode(rawPassword));
		user.setRole(invitation.getRole());
		try {
			user = users.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException ex) {
			throw new ServiceException(ErrorMessages.E016);
		}
		invitation.setAcceptedAt(now);
		invitation.setAcceptedUserId(user.getId());
		userLog.recordAction(user.getId(), UserAction.INVITATION_ACCEPTED, AuditTargetType.INVITATION,
				invitation.getId());
		return UserResponse.from(user);
	}

}
