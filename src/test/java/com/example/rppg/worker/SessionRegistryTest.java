package com.example.rppg.worker;

import com.example.rppg.app.RtpIngestWorker;
import com.example.rppg.ingest.v1.TimelineEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionRegistryTest {

    @Test
    void sessionLifecycleStartAndStop() throws Exception {
        Map<Long, FakeFrameSource> sources = new ConcurrentHashMap<>();
        Map<Long, CollectingSink> sinks = new ConcurrentHashMap<>();
        SessionRegistry registry = new SessionRegistry(
                config -> {
                    FakeFrameSource source = new FakeFrameSource(8L);
                    sources.put(config.sessionId(), source);
                    return source;
                },
                sessionId -> {
                    CollectingSink sink = new CollectingSink();
                    sinks.put(sessionId, sink);
                    return sink;
                }
        );

        WorkerSessionConfig config = new WorkerSessionConfig(
                101L,
                5004,
                null,
                640,
                480,
                30.0,
                RtpIngestWorker.RtpCodec.AUTO,
                16,
                20.0,
                250L,
                "FRAME_TIMELINE"
        );

        assertTrue(registry.startSession(config));
        assertTrue(waitUntil(() -> {
            WorkerSessionStats stats = registry.snapshot(101L);
            return stats != null && stats.decodedFrames() > 0;
        }, 3_000L));
        assertTrue(waitUntil(() -> {
            CollectingSink sink = sinks.get(101L);
            return sink != null && sink.size() > 0;
        }, 3_000L));

        assertTrue(registry.stopSession(101L));
        assertEquals(0, registry.activeSessionCount());

        FakeFrameSource source = sources.get(101L);
        CollectingSink sink = sinks.get(101L);
        assertNotNull(source);
        assertNotNull(sink);
        assertTrue(source.isClosed());
        assertTrue(sink.isClosed());
    }

    @Test
    void queueBackpressureDropsFramesWhenProducerOutrunsConsumer() throws Exception {
        SessionRegistry registry = new SessionRegistry(
                config -> new FakeFrameSource(1L),
                sessionId -> new CollectingSink()
        );

        WorkerSessionConfig config = new WorkerSessionConfig(
                202L,
                5006,
                null,
                640,
                480,
                30.0,
                RtpIngestWorker.RtpCodec.AUTO,
                2,
                1.0,
                1_000L,
                "FRAME_TIMELINE"
        );

        assertTrue(registry.startSession(config));
        assertTrue(waitUntil(() -> {
            WorkerSessionStats stats = registry.snapshot(202L);
            return stats != null && stats.decodedFrames() >= 40;
        }, 5_000L));

        WorkerSessionStats stats = registry.snapshot(202L);
        assertNotNull(stats);
        assertTrue(stats.queuedDrops() > 0, "expected dropped frames due to queue pressure");

        assertTrue(registry.stopSession(202L));
    }

    @Test
    void emitsTimelineEventsOnConfiguredCadence() throws Exception {
        CollectingSink sink = new CollectingSink();
        SessionRegistry registry = new SessionRegistry(
                config -> new FakeFrameSource(5L),
                sessionId -> sink
        );

        WorkerSessionConfig config = new WorkerSessionConfig(
                303L,
                5008,
                null,
                640,
                480,
                30.0,
                RtpIngestWorker.RtpCodec.AUTO,
                24,
                20.0,
                300L,
                "FRAME_TIMELINE"
        );

        assertTrue(registry.startSession(config));
        assertTrue(waitUntil(() -> sink.size() >= 3, 5_000L));
        assertTrue(registry.stopSession(303L));

        List<TimelineEvent> events = sink.snapshot();
        assertTrue(events.size() >= 3);
        List<Long> intervalsMs = new ArrayList<>();
        for (int i = 1; i < events.size(); i++) {
            intervalsMs.add(events.get(i).getEventTimeEpochMs() - events.get(i - 1).getEventTimeEpochMs());
        }
        assertTrue(intervalsMs.stream().allMatch(ms -> ms >= 150L && ms <= 1_500L));
    }

    @Test
    void supportsMultipleSessionsConcurrently() throws Exception {
        Map<Long, CollectingSink> sinks = new ConcurrentHashMap<>();
        SessionRegistry registry = new SessionRegistry(
                config -> new FakeFrameSource(6L),
                sessionId -> {
                    CollectingSink sink = new CollectingSink();
                    sinks.put(sessionId, sink);
                    return sink;
                }
        );

        WorkerSessionConfig first = new WorkerSessionConfig(
                401L,
                5010,
                null,
                640,
                480,
                30.0,
                RtpIngestWorker.RtpCodec.AUTO,
                24,
                12.0,
                400L,
                "FRAME_TIMELINE"
        );
        WorkerSessionConfig second = new WorkerSessionConfig(
                402L,
                5012,
                null,
                640,
                480,
                30.0,
                RtpIngestWorker.RtpCodec.AUTO,
                24,
                12.0,
                400L,
                "FRAME_TIMELINE"
        );

        assertTrue(registry.startSession(first));
        assertTrue(registry.startSession(second));
        assertEquals(2, registry.activeSessionCount());
        assertTrue(waitUntil(() -> {
            CollectingSink s1 = sinks.get(401L);
            CollectingSink s2 = sinks.get(402L);
            return s1 != null && s2 != null && s1.size() > 0 && s2.size() > 0;
        }, 5_000L));

        registry.stopAll();
        assertEquals(0, registry.activeSessionCount());
    }

    private static boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25L);
        }
        return condition.getAsBoolean();
    }

    private static final class FakeFrameSource implements DecodedFrameSource {
        private final long framePeriodMs;
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private FakeFrameSource(long framePeriodMs) {
            this.framePeriodMs = framePeriodMs;
        }

        @Override
        public void start() {
            started.set(true);
        }

        @Override
        public long grabFrameEpochMs() throws Exception {
            if (closed.get()) {
                throw new IllegalStateException("source closed");
            }
            if (!started.get()) {
                return -1L;
            }
            if (framePeriodMs > 0L) {
                Thread.sleep(framePeriodMs);
            }
            return System.currentTimeMillis();
        }

        @Override
        public void close() {
            started.set(false);
            closed.set(true);
        }

        private boolean isClosed() {
            return closed.get();
        }
    }

    private static final class CollectingSink implements TimelineEventSink {
        private final List<TimelineEvent> events = Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean closed = new AtomicBoolean(false);

        @Override
        public void send(TimelineEvent event) {
            events.add(event);
        }

        @Override
        public void close() {
            closed.set(true);
        }

        private int size() {
            synchronized (events) {
                return events.size();
            }
        }

        private List<TimelineEvent> snapshot() {
            synchronized (events) {
                return new ArrayList<>(events);
            }
        }

        private boolean isClosed() {
            return closed.get();
        }
    }
}
