package com.ayshriv.selfhostcrm.repository;

import com.ayshriv.selfhostcrm.domain.Role;
import com.ayshriv.selfhostcrm.domain.User;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/** Filters of the user list. Soft-deleted users are excluded by the entity's {@code @SQLRestriction}. */
public final class UserSpecifications {

	private UserSpecifications() {
	}

	public static Specification<User> filter(Role role, Boolean enabled, String search) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (role != null) {
				predicates.add(cb.equal(root.get("role"), role));
			}
			if (enabled != null) {
				predicates.add(cb.equal(root.get("enabled"), enabled));
			}
			if (search != null && !search.isBlank()) {
				String pattern = "%" + escapeLike(search.trim().toLowerCase(Locale.ROOT)) + "%";
				predicates.add(cb.or(cb.like(cb.lower(root.get("displayName")), pattern, '\\'),
						cb.like(cb.lower(root.get("email")), pattern, '\\')));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}

	/** Makes user input match literally: {@code %}, {@code _} and the escape character itself. */
	static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
