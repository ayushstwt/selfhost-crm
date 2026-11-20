package com.ayshriv.selfhostcrm;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@SpringBootTest
class SelfhostCrmApplicationTests {

	@Test
	void contextLoads() {
	}

}
