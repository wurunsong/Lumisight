package com.lumisight.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.lumisight")
@EnableScheduling
public class LumisightApplication {

    public static void main(String[] args) {
        SpringApplication.run(LumisightApplication.class, args);
    }
}
