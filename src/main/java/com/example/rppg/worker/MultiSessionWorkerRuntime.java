package com.example.rppg.worker;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public final class MultiSessionWorkerRuntime implements AutoCloseable {
    private final GrpcTimelineEventClient grpcClient;
    private final SessionRegistry registry;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public MultiSessionWorkerRuntime(String apiHost, int apiPort) {
        this.grpcClient = new GrpcTimelineEventClient(apiHost, apiPort);
        this.registry = new SessionRegistry(new RtpFrameSourceFactory(), grpcClient);
    }

    public boolean startSessions(List<WorkerSessionConfig> configs) {
        boolean allStarted = true;
        for (WorkerSessionConfig config : configs) {
            boolean started = registry.startSession(config);
            allStarted &= started;
        }
        return allStarted;
    }

    public int activeSessionCount() {
        return registry.activeSessionCount();
    }

    public void runUntilInterrupted(long statusLogIntervalMs) {
        long safeInterval = Math.max(500L, statusLogIntervalMs);
        while (!Thread.currentThread().isInterrupted() && !closed.get()) {
            try {
                TimeUnit.MILLISECONDS.sleep(safeInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            logStatus();
        }
    }

    public void logStatus() {
        List<WorkerSessionStats> stats = registry.snapshots();
        if (stats.isEmpty()) {
            log.info("Worker runtime status: no active sessions.");
            return;
        }
        for (WorkerSessionStats stat : stats) {
            log.info(
                    "Worker session {}: running={}, decoded={}, processed={}, dropped={}, emitted={}, queue={}",
                    stat.sessionId(),
                    stat.running(),
                    stat.decodedFrames(),
                    stat.processedFrames(),
                    stat.queuedDrops(),
                    stat.emittedEvents(),
                    stat.queueSize()
            );
        }
        double totalDecoded = stats.stream().mapToLong(WorkerSessionStats::decodedFrames).sum();
        double totalProcessed = stats.stream().mapToLong(WorkerSessionStats::processedFrames).sum();
        double dropRatio = totalDecoded <= 0.0 ? 0.0 : stats.stream().mapToLong(WorkerSessionStats::queuedDrops).sum() / totalDecoded;
        log.info(
                "Worker aggregate: sessions={}, decoded={}, processed={}, dropRatio={}",
                stats.size(),
                (long) totalDecoded,
                (long) totalProcessed,
                String.format(Locale.US, "%.3f", dropRatio)
        );
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        registry.stopAll();
        grpcClient.close();
    }
}
