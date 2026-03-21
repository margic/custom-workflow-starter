package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sample application demonstrating the Anax Kogito Spring Boot Starter.
 */
@SpringBootApplication(scanBasePackages = {"com.example", "org.kie.kogito"})
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
