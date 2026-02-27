package com.example.signal;

public interface RppgSignalExtractor {
    SignalMethod method();

    double extract(RoiStats roiStats);

    void reset();
}
