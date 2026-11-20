package com.ayshriv.selfhostcrm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.io.Serializable;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.Hibernate;
import org.hibernate.proxy.HibernateProxy;

/**
 * Common fields and lifecycle callbacks for all JPA entities. Entities are never returned from
 * controllers (use DTO records), so there are deliberately no Jackson annotations here.
 * Timestamps are UTC instants; defaults live in field initializers, not in the callbacks.
 */
@Getter
@Setter
@MappedSuperclass
public abstract class BaseEntity implements Serializable {

	private static final long serialVersionUID = 1L;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@Column(name = "created_on", nullable = false, updatable = false)
	private Instant createdOn;

	@Column(name = "updated_on", nullable = false)
	private Instant updatedOn;

	@Column(name = "is_active", nullable = false)
	private boolean active = true;

	@Column(name = "is_deleted", nullable = false)
	private boolean deleted = false;

	protected BaseEntity() {
	}

	/** Lightweight reference to an existing row (for example to set a foreign key). */
	protected BaseEntity(Long id) {
		this.id = id;
	}

	@PrePersist
	protected void onCreate() {
		Instant now = Instant.now();
		createdOn = now;
		updatedOn = now;
	}

	@PreUpdate
	protected void onUpdate() {
		updatedOn = Instant.now();
	}

	public boolean isNew() {
		return id == null;
	}

	@Override
	public final boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
			return false;
		}
		Long otherId = o instanceof HibernateProxy proxy
				? (Long) proxy.getHibernateLazyInitializer().getIdentifier()
				: ((BaseEntity) o).id;
		Long thisId = this instanceof HibernateProxy self
				? (Long) self.getHibernateLazyInitializer().getIdentifier()
				: id;
		return thisId != null && thisId.equals(otherId);
	}

	@Override
	public final int hashCode() {
		return Hibernate.getClass(this).hashCode();
	}

}
