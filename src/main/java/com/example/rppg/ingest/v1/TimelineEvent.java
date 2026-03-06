package com.example.rppg.ingest.v1;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class TimelineEvent {
    private static final TimelineEvent DEFAULT_INSTANCE = new TimelineEvent(0L, 0L, "", 0.0, 0.0, "");

    private final long sessionId;
    private final long eventTimeEpochMs;
    private final String eventType;
    private final double bpm;
    private final double quality;
    private final String payloadJson;

    private TimelineEvent(
            long sessionId,
            long eventTimeEpochMs,
            String eventType,
            double bpm,
            double quality,
            String payloadJson
    ) {
        this.sessionId = sessionId;
        this.eventTimeEpochMs = eventTimeEpochMs;
        this.eventType = eventType == null ? "" : eventType;
        this.bpm = bpm;
        this.quality = quality;
        this.payloadJson = payloadJson == null ? "" : payloadJson;
    }

    public long getSessionId() {
        return sessionId;
    }

    public long getEventTimeEpochMs() {
        return eventTimeEpochMs;
    }

    public String getEventType() {
        return eventType;
    }

    public double getBpm() {
        return bpm;
    }

    public double getQuality() {
        return quality;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static TimelineEvent getDefaultInstance() {
        return DEFAULT_INSTANCE;
    }

    byte[] toByteArray() {
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeLong(sessionId);
            out.writeLong(eventTimeEpochMs);
            writeString(out, eventType);
            out.writeDouble(bpm);
            out.writeDouble(quality);
            writeString(out, payloadJson);
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize TimelineEvent", e);
        }
    }

    static TimelineEvent parseFrom(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            long sessionId = in.readLong();
            long eventTimeEpochMs = in.readLong();
            String eventType = readString(in);
            double bpm = in.readDouble();
            double quality = in.readDouble();
            String payload = readString(in);
            return new TimelineEvent(sessionId, eventTimeEpochMs, eventType, bpm, quality, payload);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse TimelineEvent", e);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) {
            throw new IOException("Negative string length");
        }
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static final class Builder {
        private long sessionId;
        private long eventTimeEpochMs;
        private String eventType = "";
        private double bpm;
        private double quality;
        private String payloadJson = "";

        private Builder() {
        }

        public Builder setSessionId(long sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder setEventTimeEpochMs(long eventTimeEpochMs) {
            this.eventTimeEpochMs = eventTimeEpochMs;
            return this;
        }

        public Builder setEventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder setBpm(double bpm) {
            this.bpm = bpm;
            return this;
        }

        public Builder setQuality(double quality) {
            this.quality = quality;
            return this;
        }

        public Builder setPayloadJson(String payloadJson) {
            this.payloadJson = payloadJson;
            return this;
        }

        public TimelineEvent build() {
            return new TimelineEvent(sessionId, eventTimeEpochMs, eventType, bpm, quality, payloadJson);
        }
    }
}
