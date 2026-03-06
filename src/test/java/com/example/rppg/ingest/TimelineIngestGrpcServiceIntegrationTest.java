package com.example.rppg.ingest;

import com.example.rppg.ingest.v1.IngestAck;
import com.example.rppg.ingest.v1.TimelineEvent;
import com.example.rppg.ingest.v1.TimelineIngestServiceGrpc;
import com.example.rppg.persistence.SessionEntity;
import com.example.rppg.persistence.SessionEventEntity;
import com.example.rppg.persistence.SessionEventRepository;
import com.example.rppg.persistence.SessionRepository;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TimelineIngestGrpcServiceIntegrationTest {
    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private SessionEventRepository sessionEventRepository;

    @Test
    void ingestTimelinePersistsEvents() throws Exception {
        IngestProperties properties = new IngestProperties();
        properties.setBatchSize(2);
        properties.setAllowedSessionStatuses(List.of("RUNNING"));
        TimelineIngestGrpcService service = new TimelineIngestGrpcService(
                sessionRepository,
                sessionEventRepository,
                properties
        );

        String serverName = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(service)
                .build()
                .start();
        ManagedChannel channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();

        try {
            SessionEntity session = new SessionEntity();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            session.setStartedAt(now);
            session.setCreatedAt(now);
            session.setUpdatedAt(now);
            session.setSource("test");
            session.setStatus("RUNNING");
            session = sessionRepository.saveAndFlush(session);

            TimelineIngestServiceGrpc.TimelineIngestServiceStub stub = TimelineIngestServiceGrpc.newStub(channel);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<IngestAck> ackRef = new AtomicReference<>();
            AtomicReference<Throwable> errorRef = new AtomicReference<>();

            StreamObserver<IngestAck> responseObserver = new StreamObserver<>() {
                @Override
                public void onNext(IngestAck value) {
                    ackRef.set(value);
                }

                @Override
                public void onError(Throwable t) {
                    errorRef.set(t);
                    done.countDown();
                }

                @Override
                public void onCompleted() {
                    done.countDown();
                }
            };

            StreamObserver<TimelineEvent> requestObserver = stub.ingestTimeline(responseObserver);
            long baseMs = System.currentTimeMillis();
            requestObserver.onNext(TimelineEvent.newBuilder()
                    .setSessionId(session.getId())
                    .setEventTimeEpochMs(baseMs)
                    .setEventType("FRAME")
                    .setBpm(72.1)
                    .setQuality(0.81)
                    .setPayloadJson("{\"i\":1}")
                    .build());
            requestObserver.onNext(TimelineEvent.newBuilder()
                    .setSessionId(session.getId())
                    .setEventTimeEpochMs(baseMs + 1000)
                    .setEventType("FRAME")
                    .setBpm(72.4)
                    .setQuality(0.82)
                    .setPayloadJson("{\"i\":2}")
                    .build());
            requestObserver.onNext(TimelineEvent.newBuilder()
                    .setSessionId(session.getId())
                    .setEventTimeEpochMs(baseMs + 2000)
                    .setEventType("FRAME")
                    .setBpm(72.6)
                    .setQuality(0.83)
                    .setPayloadJson("{\"i\":3}")
                    .build());
            requestObserver.onCompleted();

            assertTrue(done.await(5, TimeUnit.SECONDS), "gRPC stream did not finish");
            assertNull(errorRef.get(), "gRPC returned error");

            IngestAck ack = ackRef.get();
            assertNotNull(ack, "expected ingest ack");
            assertEquals(3L, ack.getReceivedEvents());
            assertEquals(3L, ack.getPersistedEvents());
            assertEquals(3L, sessionEventRepository.count());

            List<SessionEventEntity> persisted = sessionEventRepository.findBySessionIdOrderByEventTimeAsc(session.getId());
            assertEquals(3, persisted.size());
            assertEquals("{\"i\":1}", persisted.get(0).getPayloadJson());
            assertEquals("{\"i\":2}", persisted.get(1).getPayloadJson());
            assertEquals("{\"i\":3}", persisted.get(2).getPayloadJson());
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }
}
