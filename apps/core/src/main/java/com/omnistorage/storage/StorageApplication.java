package com.omnistorage.storage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulith;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@Modulith(
		sharedModules = {"core"},
		additionalPackages = {"modules"}
)
@SpringBootApplication(scanBasePackages = "com.omnistorage.storage")
public class StorageApplication {

	public static void main(String[] args) {
		SpringApplication.run(StorageApplication.class, args);
	}

}
