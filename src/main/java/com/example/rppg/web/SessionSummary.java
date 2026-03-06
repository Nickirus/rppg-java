package com.example.rppg.web;

public record SessionSummary(
        long sessionId,
        String status,
        String source,
        long createdAtEpochMs,
        long startedAtEpochMs,
        Long endedAtEpochMs
) {
}
