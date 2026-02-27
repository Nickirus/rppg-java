package com.example.rppg.app;

import com.example.signal.BpmStatus;

import java.time.Instant;
import java.util.List;

public record RppgSnapshot(
        String timestamp,
        boolean running,
        double avgG,
        double bpm,
        double rawBpm,
        BpmStatus bpmStatus,
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
                BpmStatus.INVALID,
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
