package com.bic.cloud.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class BicloudWorkerApplication {

	public static void main(String[] args) {
		SpringApplication.run(BicloudWorkerApplication.class, args);
	}

}
