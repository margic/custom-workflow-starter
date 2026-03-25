package com.example.demo;

import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Map;

@Component("greetingService")
public class GreetingService {

    public Map<String, Object> greet(Map<String, Object> params) {
        String name = (String) params.getOrDefault("name", "World");
        return Map.of("greeting", "Hello, " + name + "!");
    }

    public Map<String, Object> getCurrentMinute(Map<String, Object> params) {
        int minute = LocalTime.now().getMinute();
        return Map.of("currentMinute", minute);
    }
}
