package com.example.rppg.app;

import com.example.signal.AutoModeState;
import com.example.signal.BpmStatus;
import com.example.signal.SignalMethod;
import com.example.rppg.vision.RoiMode;

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
        ProcessingStatus processingStatus,
        double motionScore,
        RoiMode roiMode,
        List<Double> roiWeights,
        double quality,
        double skinCoverage,
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
                ProcessingStatus.NORMAL,
                0.0,
                RoiMode.MULTI,
                List.of(0.3, 0.35, 0.35),
                0.0,
                1.0,
                0.0,
                0.0,
                List.of(),
                "",
                0.0,
                0L
        );
    }
}
