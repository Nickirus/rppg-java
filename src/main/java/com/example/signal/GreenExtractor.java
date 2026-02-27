package com.example.signal;

public final class GreenExtractor implements RppgSignalExtractor {
    @Override
    public double extract(RoiStats roiStats) {
        return roiStats.meanG();
    }

    @Override
    public void reset() {
        // stateless
    }
}
