package com.example.rppg.ingest.v1;

import io.grpc.BindableService;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.stub.AbstractAsyncStub;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Function;

import static io.grpc.MethodDescriptor.generateFullMethodName;

public final class TimelineIngestServiceGrpc {
    private TimelineIngestServiceGrpc() {
    }

    public static final String SERVICE_NAME = "rppg.ingest.v1.TimelineIngestService";

    private static volatile MethodDescriptor<TimelineEvent, IngestAck> ingestTimelineMethod;

    public static MethodDescriptor<TimelineEvent, IngestAck> getIngestTimelineMethod() {
        MethodDescriptor<TimelineEvent, IngestAck> local = ingestTimelineMethod;
        if (local == null) {
            synchronized (TimelineIngestServiceGrpc.class) {
                local = ingestTimelineMethod;
                if (local == null) {
                    ingestTimelineMethod = local = MethodDescriptor.<TimelineEvent, IngestAck>newBuilder()
                            .setType(MethodDescriptor.MethodType.CLIENT_STREAMING)
                            .setFullMethodName(generateFullMethodName(SERVICE_NAME, "IngestTimeline"))
                            .setSampledToLocalTracing(true)
                            .setRequestMarshaller(new BinaryMarshaller<>(TimelineEvent::toByteArray, TimelineEvent::parseFrom))
                            .setResponseMarshaller(new BinaryMarshaller<>(IngestAck::toByteArray, IngestAck::parseFrom))
                            .build();
                }
            }
        }
        return local;
    }

    public static TimelineIngestServiceStub newStub(Channel channel) {
        return new TimelineIngestServiceStub(channel, CallOptions.DEFAULT);
    }

    public abstract static class TimelineIngestServiceImplBase implements BindableService {
        public StreamObserver<TimelineEvent> ingestTimeline(StreamObserver<IngestAck> responseObserver) {
            return ServerCalls.asyncUnimplementedStreamingCall(getIngestTimelineMethod(), responseObserver);
        }

        @Override
        public final ServerServiceDefinition bindService() {
            return ServerServiceDefinition.builder(getServiceDescriptor())
                    .addMethod(
                            getIngestTimelineMethod(),
                            ServerCalls.asyncClientStreamingCall(
                                    new MethodHandlers<>(this, METHODID_INGEST_TIMELINE)))
                    .build();
        }
    }

    public static final class TimelineIngestServiceStub extends AbstractAsyncStub<TimelineIngestServiceStub> {
        private TimelineIngestServiceStub(Channel channel, CallOptions callOptions) {
            super(channel, callOptions);
        }

        @Override
        protected TimelineIngestServiceStub build(Channel channel, CallOptions callOptions) {
            return new TimelineIngestServiceStub(channel, callOptions);
        }

        public StreamObserver<TimelineEvent> ingestTimeline(StreamObserver<IngestAck> responseObserver) {
            return ClientCalls.asyncClientStreamingCall(
                    getChannel().newCall(getIngestTimelineMethod(), getCallOptions()),
                    responseObserver
            );
        }
    }

    private static final int METHODID_INGEST_TIMELINE = 0;

    private static final class MethodHandlers<Req, Resp> implements
            ServerCalls.ClientStreamingMethod<Req, Resp> {
        private final TimelineIngestServiceImplBase serviceImpl;
        private final int methodId;

        private MethodHandlers(TimelineIngestServiceImplBase serviceImpl, int methodId) {
            this.serviceImpl = serviceImpl;
            this.methodId = methodId;
        }

        @Override
        @SuppressWarnings("unchecked")
        public StreamObserver<Req> invoke(StreamObserver<Resp> responseObserver) {
            if (methodId != METHODID_INGEST_TIMELINE) {
                throw new AssertionError();
            }
            return (StreamObserver<Req>) serviceImpl.ingestTimeline(
                    (StreamObserver<IngestAck>) responseObserver
            );
        }
    }

    private static volatile ServiceDescriptor serviceDescriptor;

    public static ServiceDescriptor getServiceDescriptor() {
        ServiceDescriptor local = serviceDescriptor;
        if (local == null) {
            synchronized (TimelineIngestServiceGrpc.class) {
                local = serviceDescriptor;
                if (local == null) {
                    serviceDescriptor = local = ServiceDescriptor.newBuilder(SERVICE_NAME)
                            .addMethod(getIngestTimelineMethod())
                            .build();
                }
            }
        }
        return local;
    }

    private static final class BinaryMarshaller<T> implements MethodDescriptor.Marshaller<T> {
        private final Function<T, byte[]> serializer;
        private final Function<byte[], T> parser;

        private BinaryMarshaller(Function<T, byte[]> serializer, Function<byte[], T> parser) {
            this.serializer = serializer;
            this.parser = parser;
        }

        @Override
        public InputStream stream(T value) {
            return new ByteArrayInputStream(serializer.apply(value));
        }

        @Override
        public T parse(InputStream stream) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                stream.transferTo(baos);
                return parser.apply(baos.toByteArray());
            } catch (IOException e) {
                throw new IllegalStateException("Failed to parse gRPC payload", e);
            }
        }
    }
}
