package com.ayshriv.selfhostcrm;

import org.springframework.boot.SpringApplication;

public class TestSelfhostCrmApplication {

	public static void main(String[] args) {
		SpringApplication.from(SelfhostCrmApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
