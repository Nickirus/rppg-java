package com.example.signal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IlluminationCompensatorTest {

    @Test
    void compensation_reducesSharedBrightnessModulation() {
        double fs = 30.0;
        int n = 900;
        double heartHz = 1.2;
        double illumHz = 0.25;

        IlluminationCompensator compensator = new IlluminationCompensator((int) (30 * fs));

        double[] raw = new double[n];
        double[] compensated = new double[n];
        double[] illum = new double[n];

        for (int i = 0; i < n; i++) {
            double t = i / fs;
            double m = Math.sin(2.0 * Math.PI * illumHz * t);
            double h = Math.sin(2.0 * Math.PI * heartHz * t);
            double x = h + 0.9 * m;
            double bg = m;

            IlluminationCompensator.Result result = compensator.update(x, bg);
            raw[i] = x;
            compensated[i] = result.compensatedSample();
            illum[i] = m;
        }

        double rawCoupling = projectionAmplitude(raw, illum);
        double compensatedCoupling = projectionAmplitude(compensated, illum);

        assertTrue(compensatedCoupling < rawCoupling * 0.6,
                "Expected illumination coupling to decrease after compensation");
    }

    private static double projectionAmplitude(double[] signal, double[] basis) {
        int n = Math.min(signal.length, basis.length);
        double dot = 0.0;
        double norm2 = 0.0;
        for (int i = 0; i < n; i++) {
            dot += signal[i] * basis[i];
            norm2 += basis[i] * basis[i];
        }
        if (norm2 <= 1e-12) {
            return 0.0;
        }
        return Math.abs(dot / Math.sqrt(norm2));
    }
}
