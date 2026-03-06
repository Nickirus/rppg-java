package com.example.rppg.worker;

import com.example.rppg.ingest.v1.TimelineEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
final class WorkerSession {
    private final WorkerSessionConfig config;
    private final DecodedFrameSource frameSource;
    private final TimelineEventSink eventSink;
    private final ArrayBlockingQueue<Long> queue;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong decodedFrames = new AtomicLong(0L);
    private final AtomicLong droppedFrames = new AtomicLong(0L);
    private final AtomicLong processedFrames = new AtomicLong(0L);
    private final AtomicLong emittedEvents = new AtomicLong(0L);

    private Thread decodeThread;
    private Thread processThread;

    WorkerSession(WorkerSessionConfig config, DecodedFrameSource frameSource, TimelineEventSink eventSink) {
        this.config = config;
        this.frameSource = frameSource;
        this.eventSink = eventSink;
        this.queue = new ArrayBlockingQueue<>(config.queueCapacity());
    }

    void start() throws Exception {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        frameSource.start();
        decodeThread = new Thread(this::decodeLoop, "worker-decode-" + config.sessionId());
        processThread = new Thread(this::processLoop, "worker-process-" + config.sessionId());
        decodeThread.setDaemon(true);
        processThread.setDaemon(true);
        decodeThread.start();
        processThread.start();
        log.info(
                "Worker session started: sessionId={}, videoPort={}, queueCapacity={}, targetFps={}, emitIntervalMs={}",
                config.sessionId(),
                config.videoPort(),
                config.queueCapacity(),
                String.format(Locale.US, "%.2f", config.targetProcessingFps()),
                config.emitIntervalMs()
        );
    }

    void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            frameSource.close();
        } catch (Exception ignored) {
            // best effort
        }
        joinQuietly(decodeThread);
        joinQuietly(processThread);
        eventSink.close();
        log.info(
                "Worker session stopped: sessionId={}, decoded={}, processed={}, dropped={}, emitted={}",
                config.sessionId(),
                decodedFrames.get(),
                processedFrames.get(),
                droppedFrames.get(),
                emittedEvents.get()
        );
    }

    WorkerSessionStats stats() {
        return new WorkerSessionStats(
                config.sessionId(),
                running.get(),
                decodedFrames.get(),
                droppedFrames.get(),
                processedFrames.get(),
                emittedEvents.get(),
                queue.size()
        );
    }

    private void decodeLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                long ts = frameSource.grabFrameEpochMs();
                if (ts <= 0L) {
                    continue;
                }
                decodedFrames.incrementAndGet();
                if (!queue.offer(ts)) {
                    queue.poll();
                    droppedFrames.incrementAndGet();
                    queue.offer(ts);
                }
            } catch (Exception e) {
                log.warn("Decode loop error for sessionId={}: {}", config.sessionId(), e.getMessage());
                running.set(false);
                safeCloseResources();
                return;
            }
        }
    }

    private void processLoop() {
        long intervalNs = Math.max(1L, Math.round(1_000_000_000.0 / config.targetProcessingFps()));
        long nextTickNs = System.nanoTime();
        long nextEmitNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.emitIntervalMs());

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            long nowNs = System.nanoTime();
            long sleepNs = nextTickNs - nowNs;
            if (sleepNs > 0) {
                sleepNanos(sleepNs);
            } else {
                nextTickNs = nowNs;
            }
            nextTickNs += intervalNs;

            Long frameTs = queue.poll();
            if (frameTs != null) {
                processedFrames.incrementAndGet();
            }

            long currentNs = System.nanoTime();
            if (currentNs >= nextEmitNs) {
                try {
                    sendTimelineEvent();
                } catch (Exception e) {
                    log.warn("Event emit error for sessionId={}: {}", config.sessionId(), e.getMessage());
                    running.set(false);
                    safeCloseResources();
                    return;
                }
                nextEmitNs = currentNs + TimeUnit.MILLISECONDS.toNanos(config.emitIntervalMs());
            }
        }
    }

    private void sendTimelineEvent() {
        WorkerSessionStats s = stats();
        String payload = String.format(
                Locale.US,
                "{\"decodedFrames\":%d,\"processedFrames\":%d,\"droppedFrames\":%d,\"queueSize\":%d}",
                s.decodedFrames(),
                s.processedFrames(),
                s.queuedDrops(),
                s.queueSize()
        );
        TimelineEvent event = TimelineEvent.newBuilder()
                .setSessionId(config.sessionId())
                .setEventTimeEpochMs(System.currentTimeMillis())
                .setEventType(config.eventType())
                .setPayloadJson(payload)
                .build();
        eventSink.send(event);
        emittedEvents.incrementAndGet();
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(1_500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepNanos(long nanos) {
        long millis = nanos / 1_000_000L;
        int nanosPart = (int) (nanos % 1_000_000L);
        try {
            Thread.sleep(millis, nanosPart);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void safeCloseResources() {
        try {
            frameSource.close();
        } catch (Exception ignored) {
            // best effort
        }
        eventSink.close();
    }
}
