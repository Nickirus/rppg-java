package com.example.rppg.ingest.v1;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class IngestAck {
    private static final IngestAck DEFAULT_INSTANCE = new IngestAck(0L, 0L);

    private final long receivedEvents;
    private final long persistedEvents;

    private IngestAck(long receivedEvents, long persistedEvents) {
        this.receivedEvents = receivedEvents;
        this.persistedEvents = persistedEvents;
    }

    public long getReceivedEvents() {
        return receivedEvents;
    }

    public long getPersistedEvents() {
        return persistedEvents;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static IngestAck getDefaultInstance() {
        return DEFAULT_INSTANCE;
    }

    byte[] toByteArray() {
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeLong(receivedEvents);
            out.writeLong(persistedEvents);
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize IngestAck", e);
        }
    }

    static IngestAck parseFrom(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            long received = in.readLong();
            long persisted = in.readLong();
            return new IngestAck(received, persisted);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse IngestAck", e);
        }
    }

    public static final class Builder {
        private long receivedEvents;
        private long persistedEvents;

        private Builder() {
        }

        public Builder setReceivedEvents(long receivedEvents) {
            this.receivedEvents = receivedEvents;
            return this;
        }

        public Builder setPersistedEvents(long persistedEvents) {
            this.persistedEvents = persistedEvents;
            return this;
        }

        public IngestAck build() {
            return new IngestAck(receivedEvents, persistedEvents);
        }
    }
}
