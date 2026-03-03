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

    @Test
    void estimate_keepsStablePeakWithSlowIlluminationDrift() {
        double fs = 30.0;
        int n = 900;
        double hrHz = 1.2; // 72 bpm
        double driftHz = 0.05;

        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            double t = i / fs;
            double illumination = 1.0 + 0.5 * Math.sin(2.0 * Math.PI * driftHz * t);
            x[i] = 20.0 + illumination * Math.sin(2.0 * Math.PI * hrHz * t);
        }

        HeartRateEstimator est = new HeartRateEstimator(fs, 0.8, 2.5, true, 1e-6);
        HeartRateEstimator.Result r = est.estimate(x);

        assertTrue(r.valid(), r.reason());
        assertEquals(72.0, r.bpm(), 4.0);
    }
}
