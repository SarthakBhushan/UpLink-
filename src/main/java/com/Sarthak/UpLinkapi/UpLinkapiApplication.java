package com.Sarthak.UpLinkapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class UpLinkapiApplication {

	public static void main(String[] args) {
		SpringApplication.run(UpLinkapiApplication.class, args);
	}

}
