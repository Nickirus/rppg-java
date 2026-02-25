package com.example.rppg.web;

import com.example.rppg.app.Config;
import com.example.rppg.app.RppgEngine;
import com.example.rppg.app.RppgSnapshot;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class WebUiStateService {
    private final RppgEngine engine = new RppgEngine(Config.defaults());
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "web-ui-ticker");
        thread.setDaemon(true);
        return thread;
    });

    public WebUiStateService() {
        scheduler.scheduleAtFixedRate(this::broadcastLatestSnapshot, 0L, 1L, TimeUnit.SECONDS);
    }

    public RppgSnapshot getSnapshot() {
        return engine.getLatestSnapshot();
    }

    public RppgSnapshot start() {
        engine.start();
        RppgSnapshot snapshot = engine.getLatestSnapshot();
        broadcast(snapshot);
        return snapshot;
    }

    public RppgSnapshot stop() {
        engine.stop();
        RppgSnapshot snapshot = engine.getLatestSnapshot();
        broadcast(snapshot);
        return snapshot;
    }

    public RppgSnapshot reset() {
        engine.reset();
        RppgSnapshot snapshot = engine.getLatestSnapshot();
        broadcast(snapshot);
        return snapshot;
    }

    public SseEmitter addEmitter() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        sendSnapshot(emitter, engine.getLatestSnapshot());
        return emitter;
    }

    @PreDestroy
    public void shutdown() {
        engine.stop();
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

    private void broadcastLatestSnapshot() {
        broadcast(engine.getLatestSnapshot());
    }

    private void broadcast(RppgSnapshot updated) {
        List<SseEmitter> stale = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            if (!sendSnapshot(emitter, updated)) {
                stale.add(emitter);
            }
        }
        emitters.removeAll(stale);
    }

    private boolean sendSnapshot(SseEmitter emitter, RppgSnapshot updated) {
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
}
