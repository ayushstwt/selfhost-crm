package com.ayshriv.selfhostcrm.repository;

import com.ayshriv.selfhostcrm.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	@Query("select t from RefreshToken t join fetch t.user where t.tokenHash = :hash")
	Optional<RefreshToken> findByTokenHash(@Param("hash") String hash);

	/** Row lock: two concurrent refreshes with the same token are serialized. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from RefreshToken t where t.tokenHash = :hash")
	Optional<RefreshToken> findForUpdateByTokenHash(@Param("hash") String hash);

	/** Bulk updates skip lifecycle callbacks, so updated_on is set explicitly. */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update RefreshToken t set t.revokedAt = :now, t.updatedOn = :now "
			+ "where t.familyId = :familyId and t.revokedAt is null")
	int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update RefreshToken t set t.revokedAt = :now, t.updatedOn = :now "
			+ "where t.user.id = :userId and t.revokedAt is null")
	int revokeAllForUser(@Param("userId") Long userId, @Param("now") Instant now);

	/** Detaches live rows from expired ones so the expired rows can be deleted without breaking the FK. */
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update RefreshToken t set t.replacedById = null, t.updatedOn = :now where t.replacedById in "
			+ "(select e.id from RefreshToken e where e.expiresAt < :cutoff) and t.expiresAt >= :cutoff")
	int clearReplacementsOfExpired(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from RefreshToken t where t.expiresAt < :cutoff")
	int deleteExpired(@Param("cutoff") Instant cutoff);

}
