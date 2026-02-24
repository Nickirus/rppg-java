package com.example.signal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalQualityScorerTest {

    @Test
    void peakDominance_isHighForPureSineInBand() {
        double fs = 30.0;
        int n = 900;
        double freq = 1.4;
        double[] signal = new double[n];
        for (int i = 0; i < n; i++) {
            signal[i] = Math.sin(2.0 * Math.PI * freq * i / fs);
        }

        double quality = SignalQualityScorer.peakDominance(signal, fs, 0.8, 2.5);

        assertTrue(quality > 0.30, "Expected dominant peak quality for pure sine");
        assertTrue(quality <= 1.0);
    }

    @Test
    void peakDominance_isZeroForFlatSignal() {
        double[] signal = new double[900];
        double quality = SignalQualityScorer.peakDominance(signal, 30.0, 0.8, 2.5);
        assertEquals(0.0, quality, 1e-12);
    }
}
