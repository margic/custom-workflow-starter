package com.example.demo;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Example Spring service invoked via the {@code anax://greetingService/greet} URI.
 *
 * <p>
 * Demonstrates the {@code anax://} protocol: the workflow passes the
 * {@code name} field from its data context as an argument; the service returns a
 * {@code greeting} that is merged back into workflow data.
 */
@Component("greetingService")
public class GreetingService {

    public Map<String, Object> greet(Map<String, Object> params) {
        String name = (String) params.getOrDefault("name", "World");
        return Map.of("greeting", "Hello, " + name + "!");
    }
}
