package com.example.rppg.web;

import com.example.rppg.persistence.SessionEntity;
import com.example.rppg.persistence.SessionEventEntity;
import com.example.rppg.persistence.SessionEventRepository;
import com.example.rppg.persistence.SessionRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
@RequiredArgsConstructor
public class SessionOrchestrationService {
    private static final String STATUS_CREATED = "CREATED";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_DONE = "DONE";
    private static final int SSE_BATCH_SIZE = 200;

    private final SessionRepository sessionRepository;
    private final SessionEventRepository sessionEventRepository;
    private final ScheduledExecutorService sseScheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "session-events-sse");
        thread.setDaemon(true);
        return thread;
    });

    public SessionSummary createSession(String source) {
        OffsetDateTime now = nowUtc();
        SessionEntity session = new SessionEntity();
        session.setSource(normalizeSource(source));
        session.setStatus(STATUS_CREATED);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setStartedAt(now);
        session.setEndedAt(null);
        SessionEntity saved = sessionRepository.saveAndFlush(session);
        log.info("Session created: sessionId={}, source={}", saved.getId(), saved.getSource());
        return toSummary(saved);
    }

    public SessionSummary startSession(long sessionId) {
        SessionEntity session = getRequiredSession(sessionId);
        String currentStatus = normalizeStatus(session.getStatus());
        if (STATUS_DONE.equals(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session is already DONE");
        }
        if (!STATUS_RUNNING.equals(currentStatus)) {
            OffsetDateTime now = nowUtc();
            session.setStatus(STATUS_RUNNING);
            session.setStartedAt(now);
            session.setEndedAt(null);
            session.setUpdatedAt(now);
            session = sessionRepository.saveAndFlush(session);
            log.info("Session started: sessionId={}", sessionId);
        }
        return toSummary(session);
    }

    public SessionSummary stopSession(long sessionId) {
        SessionEntity session = getRequiredSession(sessionId);
        String currentStatus = normalizeStatus(session.getStatus());
        if (STATUS_DONE.equals(currentStatus)) {
            return toSummary(session);
        }
        if (!STATUS_RUNNING.equals(currentStatus)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Session must be RUNNING to stop, current status=" + currentStatus
            );
        }
        OffsetDateTime now = nowUtc();
        session.setStatus(STATUS_DONE);
        session.setEndedAt(now);
        session.setUpdatedAt(now);
        SessionEntity saved = sessionRepository.saveAndFlush(session);
        log.info("Session stopped: sessionId={}", sessionId);
        return toSummary(saved);
    }

    public SessionSummary getSession(long sessionId) {
        return toSummary(getRequiredSession(sessionId));
    }

    public SseEmitter streamSessionEvents(long sessionId) {
        SessionEntity initialSession = getRequiredSession(sessionId);
        SseEmitter emitter = new SseEmitter(0L);
        AtomicLong lastEventId = new AtomicLong(0L);
        AtomicReference<String> lastStatus = new AtomicReference<>("");
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

        sendSessionUpdate(emitter, initialSession);
        lastStatus.set(normalizeStatus(initialSession.getStatus()));

        Runnable pollTask = () -> {
            try {
                List<SessionEventEntity> events = sessionEventRepository.findBySessionIdAndIdGreaterThanOrderByIdAsc(
                        sessionId,
                        lastEventId.get(),
                        PageRequest.of(0, SSE_BATCH_SIZE)
                );
                for (SessionEventEntity event : events) {
                    SessionTimelineEvent payload = new SessionTimelineEvent(
                            event.getId(),
                            event.getSessionId(),
                            event.getEventTime().toInstant().toEpochMilli(),
                            event.getEventType(),
                            event.getBpm(),
                            event.getQuality()
                    );
                    emitter.send(SseEmitter.event().name("timeline").data(payload));
                    lastEventId.set(event.getId());
                }

                SessionEntity currentSession = getRequiredSession(sessionId);
                String currentStatus = normalizeStatus(currentSession.getStatus());
                if (!currentStatus.equals(lastStatus.get())) {
                    sendSessionUpdate(emitter, currentSession);
                    lastStatus.set(currentStatus);
                }
            } catch (ResponseStatusException missing) {
                completeWithError(emitter, missing);
                cancelFuture(futureRef.get());
            } catch (IOException | IllegalStateException ioError) {
                log.warn("Session SSE send failed for sessionId={}: {}", sessionId, ioError.getMessage());
                cancelFuture(futureRef.get());
            } catch (Exception e) {
                log.warn("Session SSE poll failed for sessionId={}: {}", sessionId, e.getMessage());
                completeWithError(emitter, e);
                cancelFuture(futureRef.get());
            }
        };

        ScheduledFuture<?> future = sseScheduler.scheduleAtFixedRate(pollTask, 0L, 1L, TimeUnit.SECONDS);
        futureRef.set(future);

        emitter.onCompletion(() -> cancelFuture(futureRef.get()));
        emitter.onTimeout(() -> cancelFuture(futureRef.get()));
        emitter.onError(ignored -> cancelFuture(futureRef.get()));
        return emitter;
    }

    @PreDestroy
    void shutdown() {
        sseScheduler.shutdownNow();
    }

    private SessionEntity getRequiredSession(long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: " + sessionId));
    }

    private static String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "web";
        }
        return source.trim();
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return STATUS_CREATED;
        }
        return status.trim().toUpperCase();
    }

    private static SessionSummary toSummary(SessionEntity session) {
        Long endedAt = session.getEndedAt() == null ? null : session.getEndedAt().toInstant().toEpochMilli();
        return new SessionSummary(
                session.getId(),
                normalizeStatus(session.getStatus()),
                session.getSource(),
                session.getCreatedAt().toInstant().toEpochMilli(),
                session.getStartedAt().toInstant().toEpochMilli(),
                endedAt
        );
    }

    private static void sendSessionUpdate(SseEmitter emitter, SessionEntity session) {
        try {
            emitter.send(SseEmitter.event().name("session").data(toSummary(session)));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to send session update", e);
        }
    }

    private static void completeWithError(SseEmitter emitter, Exception e) {
        try {
            emitter.completeWithError(e);
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static void cancelFuture(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    private static OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
