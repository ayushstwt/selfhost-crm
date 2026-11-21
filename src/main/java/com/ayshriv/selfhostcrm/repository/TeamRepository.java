package com.ayshriv.selfhostcrm.repository;

import com.ayshriv.selfhostcrm.domain.Team;
import com.ayshriv.selfhostcrm.web.TeamResponse;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Live teams only ({@code @SQLRestriction} on the entity). Member counts are computed in the same query. */
public interface TeamRepository extends JpaRepository<Team, Long> {

	String MEMBER_COUNT = "(select count(m) from TeamMember m, User u where u.id = m.userId and u.deleted = false "
			+ "and u.enabled = true and m.teamId = t.id)";

	String SELECT = "select new com.ayshriv.selfhostcrm.web.TeamResponse(t.id, t.name, t.description, t.createdOn, "
			+ MEMBER_COUNT + ") from Team t ";

	/** Row lock: serializes member changes, rename and delete of the same team. Take it before locking a user row. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Team t where t.id = :id")
	Optional<Team> findForUpdateById(@Param("id") Long id);

	@Query("select count(t) > 0 from Team t where lower(t.name) = lower(:name)")
	boolean existsByNameIgnoreCase(@Param("name") String name);

	@Query("select count(t) > 0 from Team t where lower(t.name) = lower(:name) and t.id <> :id")
	boolean existsByNameIgnoreCaseAndIdNot(@Param("name") String name, @Param("id") Long id);

	@Query(SELECT + "where t.id = :id")
	Optional<TeamResponse> findResponseById(@Param("id") Long id);

	/** All live teams (ADMIN). {@code pattern} is a lower-cased, escaped LIKE pattern or {@code '%'}. */
	@Query(value = SELECT + "where lower(t.name) like :pattern escape '!' order by t.name asc, t.id asc",
			countQuery = "select count(t) from Team t where lower(t.name) like :pattern escape '!'")
	Page<TeamResponse> searchAll(@Param("pattern") String pattern, Pageable pageable);

	/** Only the given teams (everyone else: the teams they belong to). */
	@Query(value = SELECT + "where t.id in :ids and lower(t.name) like :pattern escape '!' "
			+ "order by t.name asc, t.id asc",
			countQuery = "select count(t) from Team t where t.id in :ids and lower(t.name) like :pattern escape '!'")
	Page<TeamResponse> searchAmong(@Param("ids") Collection<Long> ids, @Param("pattern") String pattern,
			Pageable pageable);

}
