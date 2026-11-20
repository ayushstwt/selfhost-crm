package com.ayshriv.selfhostcrm.repository;

import com.ayshriv.selfhostcrm.domain.AuditTargetType;
import com.ayshriv.selfhostcrm.domain.UserAction;
import com.ayshriv.selfhostcrm.domain.UserLog;
import com.ayshriv.selfhostcrm.web.AuditLogResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Criteria query so optional filters never turn into untyped {@code null} parameters. The actor is
 * LEFT JOINed: an entry whose actor was soft-deleted stays visible, with a null {@code actorName}.
 */
class UserLogRepositoryCustomImpl implements UserLogRepositoryCustom {

	@PersistenceContext
	private EntityManager em;

	@Override
	public Page<AuditLogResponse> search(Long actorId, UserAction action, AuditTargetType targetType,
			Long targetId, Instant from, Instant to, Pageable pageable) {
		CriteriaBuilder cb = em.getCriteriaBuilder();

		CriteriaQuery<AuditLogResponse> query = cb.createQuery(AuditLogResponse.class);
		Root<UserLog> root = query.from(UserLog.class);
		var actor = root.join("user", JoinType.LEFT);
		query.select(cb.construct(AuditLogResponse.class, root.get("id"), root.get("createdOn"),
				root.get("action"), root.get("subAction"), root.get("userId"), actor.get("displayName"),
				root.get("targetType"), root.get("targetId")));
		query.where(predicates(cb, root, actorId, action, targetType, targetId, from, to));
		query.orderBy(cb.desc(root.get("createdOn")), cb.desc(root.get("id")));
		List<AuditLogResponse> content = em.createQuery(query).setFirstResult((int) pageable.getOffset())
				.setMaxResults(pageable.getPageSize()).getResultList();

		CriteriaQuery<Long> count = cb.createQuery(Long.class);
		Root<UserLog> countRoot = count.from(UserLog.class);
		count.select(cb.count(countRoot));
		count.where(predicates(cb, countRoot, actorId, action, targetType, targetId, from, to));
		long total = em.createQuery(count).getSingleResult();

		return new PageImpl<>(content, pageable, total);
	}

	private static Predicate[] predicates(CriteriaBuilder cb, Root<UserLog> root, Long actorId, UserAction action,
			AuditTargetType targetType, Long targetId, Instant from, Instant to) {
		List<Predicate> list = new ArrayList<>();
		if (actorId != null) {
			list.add(cb.equal(root.get("userId"), actorId));
		}
		if (action != null) {
			list.add(cb.equal(root.get("action"), action));
		}
		if (targetType != null) {
			list.add(cb.equal(root.get("targetType"), targetType));
		}
		if (targetId != null) {
			list.add(cb.equal(root.get("targetId"), targetId));
		}
		if (from != null) {
			list.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdOn"), from));
		}
		if (to != null) {
			list.add(cb.lessThanOrEqualTo(root.<Instant>get("createdOn"), to));
		}
		return list.toArray(Predicate[]::new);
	}

}
