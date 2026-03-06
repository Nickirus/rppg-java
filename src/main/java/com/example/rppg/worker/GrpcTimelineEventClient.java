package com.example.rppg.worker;

import com.example.rppg.ingest.v1.IngestAck;
import com.example.rppg.ingest.v1.TimelineEvent;
import com.example.rppg.ingest.v1.TimelineIngestServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public final class GrpcTimelineEventClient implements TimelineEventSinkFactory, AutoCloseable {
    private final ManagedChannel channel;
    private final TimelineIngestServiceGrpc.TimelineIngestServiceStub stub;

    public GrpcTimelineEventClient(String host, int port) {
        this(ManagedChannelBuilder.forAddress(host, port).usePlaintext().build());
    }

    GrpcTimelineEventClient(ManagedChannel channel) {
        this.channel = channel;
        this.stub = TimelineIngestServiceGrpc.newStub(channel);
    }

    @Override
    public TimelineEventSink open(long sessionId) {
        AtomicBoolean closed = new AtomicBoolean(false);
        StreamObserver<IngestAck> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(IngestAck value) {
                log.debug(
                        "Ingest ack: sessionId={}, received={}, persisted={}",
                        sessionId,
                        value.getReceivedEvents(),
                        value.getPersistedEvents()
                );
            }

            @Override
            public void onError(Throwable t) {
                closed.set(true);
                log.warn("gRPC ingest stream error for sessionId={}: {}", sessionId, t.getMessage());
            }

            @Override
            public void onCompleted() {
                closed.set(true);
                log.debug("gRPC ingest stream completed for sessionId={}", sessionId);
            }
        };
        StreamObserver<TimelineEvent> requestObserver = stub.ingestTimeline(responseObserver);
        return new TimelineEventSink() {
            @Override
            public void send(TimelineEvent event) {
                if (closed.get()) {
                    return;
                }
                synchronized (requestObserver) {
                    requestObserver.onNext(event);
                }
            }

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    synchronized (requestObserver) {
                        requestObserver.onCompleted();
                    }
                }
            }
        };
    }

    @Override
    public void close() {
        channel.shutdownNow();
    }
}
