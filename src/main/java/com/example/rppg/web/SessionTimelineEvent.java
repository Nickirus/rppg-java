package com.example.rppg.web;

public record SessionTimelineEvent(
        long id,
        long sessionId,
        long eventTimeEpochMs,
        String eventType,
        Double bpm,
        Double quality
) {
}
