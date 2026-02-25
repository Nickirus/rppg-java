package com.example.rppg.app;

import java.time.Instant;

public record RppgSnapshot(
        String timestamp,
        boolean running,
        double avgG,
        double bpm,
        double quality,
        double fps,
        double windowFill,
        String warnings
) {
    public static RppgSnapshot initial() {
        return new RppgSnapshot(
                Instant.now().toString(),
                false,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                "idle"
        );
    }
}
