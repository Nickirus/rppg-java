package com.example.rppg.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RequiredArgsConstructor
public class SessionRegistry {
    private final FrameSourceFactory frameSourceFactory;
    private final TimelineEventSinkFactory eventSinkFactory;
    private final Map<Long, WorkerSession> sessions = new ConcurrentHashMap<>();

    public boolean startSession(WorkerSessionConfig config) {
        WorkerSession existing = sessions.get(config.sessionId());
        if (existing != null) {
            if (existing.stats().running()) {
                log.warn("Session already running: sessionId={}", config.sessionId());
                return false;
            }
            sessions.remove(config.sessionId(), existing);
            existing.stop();
        }
        DecodedFrameSource frameSource = null;
        TimelineEventSink sink = null;
        WorkerSession session = null;
        try {
            frameSource = frameSourceFactory.create(config);
            sink = eventSinkFactory.open(config.sessionId());
            session = new WorkerSession(config, frameSource, sink);
            WorkerSession raced = sessions.putIfAbsent(config.sessionId(), session);
            if (raced != null) {
                sink.close();
                frameSource.close();
                log.warn("Session start race detected: sessionId={}", config.sessionId());
                return false;
            }
            session.start();
            return true;
        } catch (Exception e) {
            sessions.remove(config.sessionId());
            if (session != null) {
                session.stop();
            } else {
                if (sink != null) {
                    sink.close();
                }
                if (frameSource != null) {
                    try {
                        frameSource.close();
                    } catch (Exception ignored) {
                        // best effort
                    }
                }
            }
            log.warn("Failed to start sessionId={}: {}", config.sessionId(), e.getMessage());
            return false;
        }
    }

    public boolean stopSession(long sessionId) {
        WorkerSession session = sessions.remove(sessionId);
        if (session == null) {
            return false;
        }
        session.stop();
        return true;
    }

    public void stopAll() {
        List<Long> ids = new ArrayList<>(sessions.keySet());
        for (Long id : ids) {
            stopSession(id);
        }
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    public List<WorkerSessionStats> snapshots() {
        List<WorkerSessionStats> out = new ArrayList<>();
        for (WorkerSession session : sessions.values()) {
            out.add(session.stats());
        }
        return out;
    }

    public WorkerSessionStats snapshot(long sessionId) {
        WorkerSession session = sessions.get(sessionId);
        return session == null ? null : session.stats();
    }
}
