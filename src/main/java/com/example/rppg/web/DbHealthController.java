package com.example.rppg.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@Slf4j
@RequiredArgsConstructor
public class DbHealthController {
    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/api/health/db")
    public ResponseEntity<Map<String, Object>> dbHealth() {
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "UP");
            body.put("database", "postgres");
            body.put("result", result);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.warn("DB health check failed: {}", e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "DOWN");
            body.put("database", "postgres");
            body.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
    }
}
