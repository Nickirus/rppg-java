package com.example.rppg.worker;

public record WorkerSessionStats(
        long sessionId,
        boolean running,
        long decodedFrames,
        long queuedDrops,
        long processedFrames,
        long emittedEvents,
        int queueSize
) {
}
