package com.example.rppg.app;

import java.time.Instant;
import java.util.List;

public record RppgSnapshot(
        String timestamp,
        boolean running,
        double avgG,
        double bpm,
        double quality,
        double fps,
        double windowFill,
        List<String> warnings,
        String sessionFilePath,
        double sessionDurationSec,
        long sessionRowCount
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
                List.of(),
                "",
                0.0,
                0L
        );
    }
}
