package com.example.rppg.app;

import com.example.signal.AutoModeState;
import com.example.signal.BpmStatus;
import com.example.signal.SignalMethod;

import java.time.Instant;
import java.util.List;

public record RppgSnapshot(
        String timestamp,
        boolean running,
        double avgG,
        double bpm,
        double rawBpm,
        BpmStatus bpmStatus,
        SignalMethod activeSignalMethod,
        AutoModeState autoModeState,
        SignalMethod probeCandidate,
        double probeSecondsRemaining,
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
                SignalMethod.POS,
                AutoModeState.STABLE,
                null,
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
