package com.example.rppg.worker;

public interface TimelineEventSinkFactory {
    TimelineEventSink open(long sessionId);
}
