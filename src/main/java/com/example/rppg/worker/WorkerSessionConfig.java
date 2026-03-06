package com.example.rppg.worker;

import com.example.rppg.app.RtpIngestWorker;

public record WorkerSessionConfig(
        long sessionId,
        int videoPort,
        Integer audioPort,
        int expectedWidth,
        int expectedHeight,
        double expectedDecodeFps,
        RtpIngestWorker.RtpCodec codec,
        int queueCapacity,
        double targetProcessingFps,
        long emitIntervalMs,
        String eventType
) {
    public WorkerSessionConfig {
        if (sessionId <= 0) {
            throw new IllegalArgumentException("sessionId must be > 0");
        }
        if (videoPort <= 0 || videoPort > 65535) {
            throw new IllegalArgumentException("videoPort must be in [1,65535]");
        }
        if (audioPort != null && (audioPort <= 0 || audioPort > 65535)) {
            throw new IllegalArgumentException("audioPort must be in [1,65535]");
        }
        if (expectedWidth <= 0 || expectedHeight <= 0) {
            throw new IllegalArgumentException("expected width/height must be > 0");
        }
        if (!Double.isFinite(expectedDecodeFps) || expectedDecodeFps <= 0.0) {
            throw new IllegalArgumentException("expectedDecodeFps must be > 0");
        }
        if (codec == null) {
            throw new IllegalArgumentException("codec is required");
        }
        if (queueCapacity < 2) {
            throw new IllegalArgumentException("queueCapacity must be >= 2");
        }
        if (!Double.isFinite(targetProcessingFps) || targetProcessingFps <= 0.0) {
            throw new IllegalArgumentException("targetProcessingFps must be > 0");
        }
        if (emitIntervalMs < 200 || emitIntervalMs > 60_000) {
            throw new IllegalArgumentException("emitIntervalMs must be in [200,60000]");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
    }

    public static WorkerSessionConfig defaults(long sessionId, int videoPort) {
        return new WorkerSessionConfig(
                sessionId,
                videoPort,
                null,
                640,
                480,
                30.0,
                RtpIngestWorker.RtpCodec.AUTO,
                120,
                10.0,
                1000L,
                "FRAME_TIMELINE"
        );
    }
}
