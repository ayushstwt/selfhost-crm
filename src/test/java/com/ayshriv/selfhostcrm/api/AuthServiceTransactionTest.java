package com.ayshriv.selfhostcrm.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ayshriv.selfhostcrm.TestcontainersConfiguration;
import com.ayshriv.selfhostcrm.service.AuthService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * AuthService records failed logins (counter, lockout, audit) and then throws. If it ran inside a
 * transaction, that bookkeeping would roll back with the exception. This test fails if anyone makes it
 * transactional.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AuthServiceTransactionTest {

	@Autowired
	AuthService authService;

	@Test
	void authServiceIsNotTransactional() {
		assertThat(AuthService.class.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))
				.as("class-level @Transactional").isFalse();
		assertThat(AuthService.class.isAnnotationPresent(jakarta.transaction.Transactional.class))
				.as("class-level jakarta @Transactional").isFalse();
		for (Method m : AuthService.class.getDeclaredMethods()) {
			assertThat(m.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))
					.as("@Transactional on " + m.getName()).isFalse();
			assertThat(m.isAnnotationPresent(jakarta.transaction.Transactional.class))
					.as("jakarta @Transactional on " + m.getName()).isFalse();
		}
		assertThat(AopUtils.isAopProxy(authService)).as("AuthService bean is proxied").isFalse();
	}

}
