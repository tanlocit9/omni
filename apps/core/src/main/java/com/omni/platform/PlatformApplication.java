package com.omni.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.modulith.Modulith;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling
@Modulith(
		sharedModules = {"shared"},
		additionalPackages = {"modules"}
)
@EntityScan(basePackages = {
		"com.omni.platform.modules",
		"org.springframework.modulith.events.jpa"
})
@SpringBootApplication(scanBasePackages = "com.omni.platform")
public class PlatformApplication {

	public static void main(String[] args) {
		SpringApplication.run(PlatformApplication.class, args);
	}

}
