package com.example.rppg.worker;

import com.example.rppg.ingest.v1.TimelineEvent;

public interface TimelineEventSink extends AutoCloseable {
    void send(TimelineEvent event);

    @Override
    void close();
}
