package com.example.rppg.web;

import java.time.Instant;

public record WebUiSnapshot(
        String timestamp,
        boolean running,
        double bpm,
        double quality,
        double fps,
        double windowFill,
        String warnings
) {
    static WebUiSnapshot initial() {
        return new WebUiSnapshot(
                Instant.now().toString(),
                false,
                0.0,
                0.0,
                0.0,
                0.0,
                "idle"
        );
    }
}
