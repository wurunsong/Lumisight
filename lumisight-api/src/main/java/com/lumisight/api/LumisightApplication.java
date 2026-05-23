package com.lumisight.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.lumisight")
public class LumisightApplication {

    public static void main(String[] args) {
        SpringApplication.run(LumisightApplication.class, args);
    }
}
