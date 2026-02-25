package com.example.rppg.web;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class WebUiStateService {
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong tick = new AtomicLong(0L);
    private final AtomicReference<WebUiSnapshot> snapshot = new AtomicReference<>(WebUiSnapshot.initial());
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "web-ui-ticker");
        thread.setDaemon(true);
        return thread;
    });

    public WebUiStateService() {
        scheduler.scheduleAtFixedRate(this::tickAndBroadcast, 0L, 1L, TimeUnit.SECONDS);
    }

    public WebUiSnapshot getSnapshot() {
        return snapshot.get();
    }

    public WebUiSnapshot start() {
        running.set(true);
        return updateSnapshot(0L);
    }

    public WebUiSnapshot stop() {
        running.set(false);
        return updateSnapshot(0L);
    }

    public WebUiSnapshot reset() {
        tick.set(0L);
        running.set(false);
        WebUiSnapshot resetSnapshot = new WebUiSnapshot(
                Instant.now().toString(),
                false,
                0.0,
                0.0,
                0.0,
                0.0,
                "reset"
        );
        snapshot.set(resetSnapshot);
        broadcast(resetSnapshot);
        return resetSnapshot;
    }

    public SseEmitter addEmitter() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        sendSnapshot(emitter, snapshot.get());
        return emitter;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // ignore
            }
        }
        emitters.clear();
    }

    private void tickAndBroadcast() {
        long delta = running.get() ? 1L : 0L;
        WebUiSnapshot updated = updateSnapshot(delta);
        broadcast(updated);
    }

    private WebUiSnapshot updateSnapshot(long deltaTick) {
        long currentTick = tick.addAndGet(deltaTick);
        boolean isRunning = running.get();
        double phase = currentTick / 5.0;
        double bpm = isRunning ? 72.0 + 6.0 * Math.sin(phase) : 0.0;
        double quality = isRunning ? 0.55 + 0.30 * Math.sin(phase / 2.0) : 0.0;
        double fps = isRunning ? 29.0 + 1.5 * Math.cos(phase / 3.0) : 0.0;
        double fill = isRunning ? Math.min(100.0, currentTick * 3.5) : 0.0;
        String warnings;
        if (!isRunning) {
            warnings = "stopped";
        } else if (quality < 0.2) {
            warnings = "low-confidence";
        } else {
            warnings = "";
        }

        WebUiSnapshot updated = new WebUiSnapshot(
                Instant.now().toString(),
                isRunning,
                round2(bpm),
                round3(quality),
                round2(fps),
                round1(fill),
                warnings
        );
        snapshot.set(updated);
        return updated;
    }

    private void broadcast(WebUiSnapshot updated) {
        List<SseEmitter> stale = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            if (!sendSnapshot(emitter, updated)) {
                stale.add(emitter);
            }
        }
        emitters.removeAll(stale);
    }

    private boolean sendSnapshot(SseEmitter emitter, WebUiSnapshot updated) {
        try {
            emitter.send(SseEmitter.event().name("snapshot").data(updated));
            return true;
        } catch (IOException | IllegalStateException e) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // ignore
            }
            return false;
        }
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
