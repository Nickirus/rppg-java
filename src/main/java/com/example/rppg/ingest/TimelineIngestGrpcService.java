package com.example.rppg.ingest;

import com.example.rppg.ingest.v1.IngestAck;
import com.example.rppg.ingest.v1.TimelineEvent;
import com.example.rppg.ingest.v1.TimelineIngestServiceGrpc;
import com.example.rppg.persistence.SessionEntity;
import com.example.rppg.persistence.SessionEventEntity;
import com.example.rppg.persistence.SessionEventRepository;
import com.example.rppg.persistence.SessionRepository;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
@ConditionalOnProperty(prefix = "rppg.grpc", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class TimelineIngestGrpcService extends TimelineIngestServiceGrpc.TimelineIngestServiceImplBase {
    private final SessionRepository sessionRepository;
    private final SessionEventRepository sessionEventRepository;
    private final IngestProperties ingestProperties;

    @Override
    public StreamObserver<TimelineEvent> ingestTimeline(StreamObserver<IngestAck> responseObserver) {
        return new StreamObserver<>() {
            private final int batchSize = Math.max(1, ingestProperties.getBatchSize());
            private final List<SessionEventEntity> batch = new ArrayList<>(batchSize);
            private final Set<String> allowedStatuses = buildAllowedStatuses();
            private long receivedCount;
            private long persistedCount;
            private boolean streamClosed;
            private Long validatedSessionId;
            private boolean validatedSessionAllowed;

            @Override
            public void onNext(TimelineEvent value) {
                if (streamClosed) {
                    return;
                }
                try {
                    validateEvent(value);
                    SessionEventEntity entity = toEntity(value);
                    batch.add(entity);
                    receivedCount++;
                    if (batch.size() >= batchSize) {
                        flushBatch();
                    }
                } catch (Exception e) {
                    closeWithError(e);
                }
            }

            @Override
            public void onError(Throwable t) {
                streamClosed = true;
                log.warn("IngestTimeline stream terminated by client: {}", t.getMessage());
            }

            @Override
            public void onCompleted() {
                if (streamClosed) {
                    return;
                }
                try {
                    flushBatch();
                    responseObserver.onNext(IngestAck.newBuilder()
                            .setReceivedEvents(receivedCount)
                            .setPersistedEvents(persistedCount)
                            .build());
                    responseObserver.onCompleted();
                    streamClosed = true;
                    log.info("IngestTimeline completed: received={}, persisted={}", receivedCount, persistedCount);
                } catch (Exception e) {
                    closeWithError(e);
                }
            }

            private void validateEvent(TimelineEvent event) {
                long sessionId = event.getSessionId();
                if (sessionId <= 0) {
                    throw Status.INVALID_ARGUMENT.withDescription("session_id must be > 0").asRuntimeException();
                }
                String eventType = event.getEventType() == null ? "" : event.getEventType().trim();
                if (eventType.isEmpty()) {
                    throw Status.INVALID_ARGUMENT.withDescription("event_type is required").asRuntimeException();
                }

                if (!Long.valueOf(sessionId).equals(validatedSessionId)) {
                    Optional<SessionEntity> maybeSession = sessionRepository.findById(sessionId);
                    if (maybeSession.isEmpty()) {
                        throw Status.NOT_FOUND
                                .withDescription("session_id not found: " + sessionId)
                                .asRuntimeException();
                    }
                    SessionEntity session = maybeSession.get();
                    String status = session.getStatus() == null
                            ? ""
                            : session.getStatus().trim().toUpperCase(Locale.ROOT);
                    validatedSessionAllowed = allowedStatuses.contains(status);
                    validatedSessionId = sessionId;
                }
                if (!validatedSessionAllowed) {
                    throw Status.FAILED_PRECONDITION
                            .withDescription("session status does not allow ingest for session_id=" + sessionId)
                            .asRuntimeException();
                }
            }

            private SessionEventEntity toEntity(TimelineEvent event) {
                long eventMs = event.getEventTimeEpochMs() <= 0 ? System.currentTimeMillis() : event.getEventTimeEpochMs();
                OffsetDateTime eventTime = OffsetDateTime.ofInstant(Instant.ofEpochMilli(eventMs), ZoneOffset.UTC);

                SessionEventEntity entity = new SessionEventEntity();
                entity.setSessionId(event.getSessionId());
                entity.setEventTime(eventTime);
                entity.setEventType(event.getEventType().trim());
                entity.setBpm(Double.isFinite(event.getBpm()) ? event.getBpm() : null);
                entity.setQuality(Double.isFinite(event.getQuality()) ? event.getQuality() : null);
                String payload = event.getPayloadJson();
                entity.setPayloadJson(payload == null || payload.isBlank() ? null : payload);
                return entity;
            }

            private void flushBatch() {
                if (batch.isEmpty()) {
                    return;
                }
                sessionEventRepository.saveAll(batch);
                sessionEventRepository.flush();
                persistedCount += batch.size();
                batch.clear();
            }

            private Set<String> buildAllowedStatuses() {
                Set<String> statuses = new HashSet<>();
                for (String status : ingestProperties.getAllowedSessionStatuses()) {
                    if (status == null) {
                        continue;
                    }
                    String normalized = status.trim().toUpperCase(Locale.ROOT);
                    if (!normalized.isEmpty()) {
                        statuses.add(normalized);
                    }
                }
                if (statuses.isEmpty()) {
                    statuses.add("RUNNING");
                }
                return statuses;
            }

            private void closeWithError(Exception e) {
                if (streamClosed) {
                    return;
                }
                streamClosed = true;
                if (e instanceof io.grpc.StatusRuntimeException statusRuntimeException) {
                    responseObserver.onError(statusRuntimeException);
                    log.warn("IngestTimeline validation error: {}", statusRuntimeException.getStatus().getDescription());
                    return;
                }
                responseObserver.onError(Status.INTERNAL
                        .withDescription("ingest failed")
                        .withCause(e)
                        .asRuntimeException());
                log.warn("IngestTimeline internal error: {}", e.getMessage());
            }
        };
    }
}
