package com.example.signal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HeartRateEstimatorTest {

    @Test
    void estimate_detectsKnownSineFrequency() {
        double fs = 30.0;
        int n = 900; // 30s window
        double freq = 1.5; // 90 bpm

        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            double t = i / fs;
            x[i] = Math.sin(2.0 * Math.PI * freq * t);
        }

        HeartRateEstimator est = new HeartRateEstimator(fs, 0.8, 2.5);
        HeartRateEstimator.Result r = est.estimate(x);

        assertTrue(r.valid(), r.reason());
        assertEquals(90.0, r.bpm(), 6.0, "tolerance due to bin resolution / DFT");
    }

    @Test
    void estimate_returnsInvalidForFlatSignal() {
        double[] flat = new double[900];
        HeartRateEstimator est = new HeartRateEstimator(30.0, 0.8, 2.5);

        HeartRateEstimator.Result r = est.estimate(flat);

        assertFalse(r.valid());
        assertEquals(-1, r.bin());
        assertTrue(Double.isNaN(r.bpm()));
    }
}
