package com.ayshriv.selfhostcrm.repository;

import com.ayshriv.selfhostcrm.domain.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByEmail(String email);

	boolean existsByEmail(String email);

	/** Row lock so concurrent failed logins cannot lose counter updates. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.email = :email")
	Optional<User> findForUpdateByEmail(@Param("email") String email);

	/** Includes soft-deleted users on purpose: once any user ever existed, setup stays closed. */
	@Query(value = "select exists (select 1 from users)", nativeQuery = true)
	boolean existsAnyUser();

}
