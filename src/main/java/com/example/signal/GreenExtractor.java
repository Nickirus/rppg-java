package com.example.signal;

public final class GreenExtractor implements RppgSignalExtractor {
    @Override
    public SignalMethod method() {
        return SignalMethod.GREEN;
    }

    @Override
    public double extract(RoiStats roiStats) {
        return roiStats.meanG();
    }

    @Override
    public void reset() {
        // stateless
    }
}
