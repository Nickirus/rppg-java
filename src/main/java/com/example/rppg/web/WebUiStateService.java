package com.example.rppg.web;

import com.example.rppg.app.Config;
import com.example.rppg.app.RppgEngine;
import com.example.rppg.app.RppgProperties;
import com.example.rppg.app.RppgSnapshot;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class WebUiStateService {
    private static final DateTimeFormatter SESSION_FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String CSV_HEADER = "timestamp,avgG,bpm,quality,rawBpm,bpmStatus,activeSignalMethod,autoModeState,motionScore,smoothedRectDelta,processingStatus,windowFill,fps,peakHz";

    private final RppgProperties rppgProperties;
    private final Object engineLock = new Object();
    private volatile RppgEngine engine = new RppgEngine(Config.defaults());
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "web-ui-ticker");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    void init() {
        engine = new RppgEngine(rppgProperties.toConfig());
        scheduler.scheduleAtFixedRate(this::broadcastLatestSnapshot, 0L, 1L, TimeUnit.SECONDS);
    }

    public RppgSnapshot getSnapshot() {
        return engine.getLatestSnapshot();
    }

    public boolean isRunning() {
        return engine.getLatestSnapshot().running();
    }

    public byte[] getLatestJpegFrame() {
        return engine.getLatestJpegFrame();
    }

    public RppgSnapshot start() {
        RppgSnapshot snapshot;
        synchronized (engineLock) {
            RppgEngine current = engine;
            if (current.getLatestSnapshot().running()) {
                log.info("Web control start requested but engine is already running.");
                snapshot = current.getLatestSnapshot();
            } else {
                current.stop();
                String sessionPath = nextSessionCsvPath();
                ensureSessionCsvPrepared(sessionPath);
                log.info("Session CSV created: {}", sessionPath);
                Config sessionConfig = rppgProperties.toConfig().withCsvPath(sessionPath);
                RppgEngine sessionEngine = new RppgEngine(sessionConfig);
                sessionEngine.start();
                engine = sessionEngine;
                snapshot = sessionEngine.getLatestSnapshot();
                log.info("Web control start: engine started.");
            }
        }
        broadcast(snapshot);
        return snapshot;
    }

    public RppgSnapshot stop() {
        RppgSnapshot snapshot;
        synchronized (engineLock) {
            engine.stop();
            snapshot = engine.getLatestSnapshot();
            log.info(
                    "Web control stop: engine stopped. sessionFile={}, rows={}",
                    snapshot.sessionFilePath(),
                    snapshot.sessionRowCount()
            );
        }
        broadcast(snapshot);
        return snapshot;
    }

    public RppgSnapshot reset() {
        RppgSnapshot snapshot;
        synchronized (engineLock) {
            engine.reset();
            snapshot = engine.getLatestSnapshot();
            log.info("Web control reset: engine reset.");
        }
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
        synchronized (engineLock) {
            engine.stop();
        }
        log.info("WebUiStateService shutdown: engine stopped.");
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
            log.warn("SSE stream send failed: {}", e.getMessage());
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // ignore
            }
            return false;
        }
    }

    private String nextSessionCsvPath() {
        LocalDateTime now = LocalDateTime.now();
        String ts = now.format(SESSION_FILE_TS);
        Path logsDir = Paths.get("logs");
        Path candidate = logsDir.resolve("session-" + ts + ".csv");
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = logsDir.resolve("session-" + ts + "-" + suffix + ".csv");
            suffix++;
        }
        return candidate.toString();
    }

    private void ensureSessionCsvPrepared(String sessionPath) {
        try {
            Path path = Paths.get(sessionPath).toAbsolutePath().normalize();
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(path)) {
                Files.writeString(path, CSV_HEADER + System.lineSeparator());
            } else if (Files.size(path) == 0L) {
                Files.writeString(path, CSV_HEADER + System.lineSeparator());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare session CSV: " + sessionPath, e);
        }
    }
}
