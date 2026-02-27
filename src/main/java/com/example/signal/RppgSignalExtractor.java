package com.example.signal;

public interface RppgSignalExtractor {
    double extract(RoiStats roiStats);

    void reset();
}
