package com.ayshriv.selfhostcrm;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SelfhostCrmApplication {

	public static void main(String[] args) {
		// Pin to UTC: keeps stored timestamps consistent and avoids PostgreSQL rejecting legacy zone IDs (e.g. Asia/Calcutta).
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(SelfhostCrmApplication.class, args);
	}

}
