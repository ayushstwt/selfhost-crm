package com.ayshriv.selfhostcrm.repository;

import com.ayshriv.selfhostcrm.domain.User;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

	/** Live users only (soft-deleted rows are filtered out), so a deleted user's email can be reused. */
	Optional<User> findByEmail(String email);

	boolean existsByEmail(String email);

	/** Row lock so concurrent failed logins cannot lose counter updates. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.email = :email")
	Optional<User> findForUpdateByEmail(@Param("email") String email);

	/** Row lock on the user an admin action is about to change. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.id = :id")
	Optional<User> findForUpdateById(@Param("id") Long id);

	/**
	 * Locks every enabled ADMIN row, always in id order, so two concurrent demotions, disables or deletes
	 * queue up instead of both seeing "another admin still exists". Take this lock before locking the target.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.role = com.ayshriv.selfhostcrm.domain.Role.ADMIN and u.enabled = true "
			+ "order by u.id")
	List<User> lockEnabledAdmins();

	/** Includes soft-deleted users on purpose: once any user ever existed, setup stays closed. */
	/** Bulk update: sets {@code updated_on} explicitly. Used to cut off several users at once. */
	@org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "update users set access_changed_at = :at, updated_on = :now where id in (:ids) and is_deleted = false",
			nativeQuery = true)
	int bumpAccessChangedAt(@Param("ids") java.util.Collection<Long> ids, @Param("at") Instant at, @Param("now") Instant now);

	@Query(value = "select exists (select 1 from users)", nativeQuery = true)
	boolean existsAnyUser();

}
