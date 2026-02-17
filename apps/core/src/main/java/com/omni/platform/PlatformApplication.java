package com.omni.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulith;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@Modulith(
		sharedModules = {"core"},
		additionalPackages = {"modules"}
)
@SpringBootApplication(scanBasePackages = "com.omni.core")
public class PlatformApplication {

	public static void main(String[] args) {
		SpringApplication.run(PlatformApplication.class, args);
	}

}
