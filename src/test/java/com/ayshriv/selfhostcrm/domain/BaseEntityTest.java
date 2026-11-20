package com.ayshriv.selfhostcrm.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.assertj.core.api.Assertions;
import org.assertj.core.data.TemporalUnitOffset;
import org.junit.jupiter.api.Test;

class BaseEntityTest {

	static class Thing extends BaseEntity {

		Thing() {
		}

		Thing(Long id) {
			super(id);
		}

	}

	static class Other extends BaseEntity {

		Other(Long id) {
			super(id);
		}

	}

	@Test
	void defaultsAreSetByInitializers() {
		Thing t = new Thing();
		assertThat(t.isActive()).isTrue();
		assertThat(t.isDeleted()).isFalse();
		assertThat(t.isNew()).isTrue();
	}

	@Test
	void prePersistSetsTimestampsButDoesNotOverwriteFlags() {
		Thing t = new Thing();
		t.setActive(false);
		t.setDeleted(true);
		t.onCreate();
		assertThat(t.getCreatedOn()).isCloseTo(Instant.now(), within());
		assertThat(t.getUpdatedOn()).isEqualTo(t.getCreatedOn());
		assertThat(t.isActive()).isFalse();
		assertThat(t.isDeleted()).isTrue();
	}

	@Test
	void preUpdateOnlyTouchesUpdatedOn() throws Exception {
		Thing t = new Thing();
		t.onCreate();
		Instant created = t.getCreatedOn();
		Thread.sleep(5);
		t.onUpdate();
		assertThat(t.getCreatedOn()).isEqualTo(created);
		assertThat(t.getUpdatedOn()).isAfter(created);
	}

	@Test
	void equalsAndHashCodeUseId() {
		assertThat(new Thing(1L)).isEqualTo(new Thing(1L));
		assertThat(new Thing(1L)).isNotEqualTo(new Thing(2L));
		assertThat(new Thing(1L)).isNotEqualTo(new Other(1L));
		assertThat(new Thing()).isNotEqualTo(new Thing());
		assertThat(new Thing(1L).hashCode()).isEqualTo(new Thing(1L).hashCode());
	}

	@Test
	void hashCodeIsStableAcrossPersist() {
		Thing t = new Thing();
		int before = t.hashCode();
		t.setId(42L);
		assertThat(t.hashCode()).isEqualTo(before);
	}

	private static TemporalUnitOffset within() {
		return Assertions.within(Duration.ofSeconds(5).toMillis(), ChronoUnit.MILLIS);
	}

}
