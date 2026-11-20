package com.ayshriv.selfhostcrm.repository;

import com.ayshriv.selfhostcrm.web.UserLogResponse;
import com.ayshriv.selfhostcrm.domain.UserLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserLogRepository extends JpaRepository<UserLog, Long>, UserLogRepositoryCustom {

	@Query(value = """
			select new com.ayshriv.selfhostcrm.web.UserLogResponse(
			    l.id, l.createdOn, l.action, l.subAction, u.displayName, u.role)
			from UserLog l join l.user u
			where u.id = :userId
			order by l.createdOn desc, l.id desc
			""", countQuery = "select count(l) from UserLog l where l.user.id = :userId")
	Page<UserLogResponse> findResponsesByUserId(@Param("userId") Long userId, Pageable pageable);

}
