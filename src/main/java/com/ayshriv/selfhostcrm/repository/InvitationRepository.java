package com.ayshriv.selfhostcrm.repository;

import com.ayshriv.selfhostcrm.domain.Invitation;
import com.ayshriv.selfhostcrm.web.InvitationResponse;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

	String PENDING_PROJECTION = """
			select new com.ayshriv.selfhostcrm.web.InvitationResponse(
			    i.id, i.email, i.role, i.invitedBy, u.displayName, i.expiresAt, i.createdOn)
			from Invitation i left join User u on u.id = i.invitedBy
			where i.acceptedAt is null and i.revokedAt is null and i.expiresAt > :now
			""";

	Optional<Invitation> findByTokenHash(String tokenHash);

	/** Row lock: two concurrent accepts of the same link are serialized, so the link works once. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from Invitation i where i.tokenHash = :hash")
	Optional<Invitation> findForUpdateByTokenHash(@Param("hash") String hash);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from Invitation i where i.id = :id")
	Optional<Invitation> findForUpdateById(@Param("id") Long id);

	/** The not accepted, not revoked invitation for this email (at most one: partial unique index). Expired ones count. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from Invitation i where i.email = :email and i.acceptedAt is null and i.revokedAt is null")
	Optional<Invitation> findOpenForUpdateByEmail(@Param("email") String email);

	@Query("select count(i) > 0 from Invitation i where i.tokenHash = :hash and i.acceptedAt is null "
			+ "and i.revokedAt is null and i.expiresAt > :now")
	boolean existsPendingByTokenHash(@Param("hash") String hash, @Param("now") Instant now);

	@Query(PENDING_PROJECTION + " and i.id = :id")
	Optional<InvitationResponse> findPendingById(@Param("now") Instant now, @Param("id") Long id);

	@Query(value = PENDING_PROJECTION + " order by i.createdOn desc, i.id desc", countQuery = """
			select count(i) from Invitation i
			where i.acceptedAt is null and i.revokedAt is null and i.expiresAt > :now""")
	Page<InvitationResponse> findPending(@Param("now") Instant now, Pageable pageable);

	@Query(value = PENDING_PROJECTION + " and i.invitedBy = :inviterId order by i.createdOn desc, i.id desc",
			countQuery = """
					select count(i) from Invitation i
					where i.acceptedAt is null and i.revokedAt is null and i.expiresAt > :now
					and i.invitedBy = :inviterId""")
	Page<InvitationResponse> findPendingByInviter(@Param("now") Instant now, @Param("inviterId") Long inviterId,
			Pageable pageable);

	/** Bulk update: skips lifecycle callbacks, so updated_on is set explicitly. */
	@Modifying(flushAutomatically = true)
	@Query("update Invitation i set i.revokedAt = :now, i.updatedOn = :now "
			+ "where i.invitedBy = :inviterId and i.acceptedAt is null and i.revokedAt is null")
	int revokePendingByInviter(@Param("inviterId") Long inviterId, @Param("now") Instant now);

}
