package com.ayshriv.selfhostcrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

/**
 * A group of people inside the one organization (not a tenant). Soft-deleted together with its memberships.
 * Names are unique case-insensitively among live teams (partial unique index on {@code lower(name)}).
 */
@Entity
@Table(name = "teams")
@SQLRestriction("is_deleted = false")
@Getter
@Setter
public class Team extends BaseEntity {

	@Column(name = "name", nullable = false, length = 100)
	private String name;

	@Column(name = "description", length = 500)
	private String description;

	public void setName(String name) {
		this.name = name == null ? null : name.trim();
	}

}
