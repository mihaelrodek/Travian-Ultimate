package com.travianbot.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Minimal liveness endpoint consumed by the frontend's backend-status badge.
 * We deliberately don't rely on Spring Actuator here — keeps the response shape stable.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "travian-bot-backend",
                "timestamp", Instant.now().toString()
        );
    }
}
