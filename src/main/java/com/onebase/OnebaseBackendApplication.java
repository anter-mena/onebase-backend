package com.onebase;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OnebaseBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(OnebaseBackendApplication.class, args);
	}

}
