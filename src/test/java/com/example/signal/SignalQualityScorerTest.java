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

    @Test
    void snr_isHighForPureSineInBand() {
        double fs = 30.0;
        int n = 900;
        double freq = 1.4;
        double[] signal = new double[n];
        for (int i = 0; i < n; i++) {
            signal[i] = Math.sin(2.0 * Math.PI * freq * i / fs);
        }

        double snr = SignalQualityScorer.snr(signal, fs, 0.8, 2.5);

        assertTrue(snr > 5.0, "Expected high SNR for pure sine");
    }

    @Test
    void snr_isLowForFlatSignal() {
        double[] signal = new double[900];

        double snr = SignalQualityScorer.snr(signal, 30.0, 0.8, 2.5);

        assertEquals(0.0, snr, 1e-12);
    }

    @Test
    void snr_isLowerForNoisyBandSignal() {
        double fs = 30.0;
        int n = 900;
        double targetHz = 1.4;
        double[] pure = new double[n];
        for (int i = 0; i < n; i++) {
            double t = i / fs;
            pure[i] = Math.sin(2.0 * Math.PI * targetHz * t);
        }
        double pureSnr = SignalQualityScorer.snr(pure, fs, 0.8, 2.5);

        double[] noisy = new double[n];
        double[] noisyFreqs = {
                0.82, 0.90, 0.98, 1.06, 1.14, 1.22, 1.30, 1.38, 1.46, 1.54,
                1.62, 1.70, 1.78, 1.86, 1.94, 2.02, 2.10, 2.18, 2.26, 2.34, 2.42
        };
        for (int i = 0; i < n; i++) {
            double t = i / fs;
            double v = 0.0;
            for (int j = 0; j < noisyFreqs.length; j++) {
                v += Math.sin(2.0 * Math.PI * noisyFreqs[j] * t + (j * 0.41));
            }
            noisy[i] = v / noisyFreqs.length;
        }
        double noisySnr = SignalQualityScorer.snr(noisy, fs, 0.8, 2.5);

        assertTrue(noisySnr < pureSnr * 0.5, "Expected noisy signal SNR to be significantly lower than pure sine");
        assertTrue(noisySnr < 8.0, "Expected noisy signal SNR to remain low, actual=" + noisySnr);
    }

    @Test
    void quality2_isHighForPureSineInBand() {
        double fs = 30.0;
        int n = 900;
        double freq = 1.4;
        double[] signal = new double[n];
        for (int i = 0; i < n; i++) {
            signal[i] = Math.sin(2.0 * Math.PI * freq * i / fs);
        }

        double quality2 = SignalQualityScorer.quality2(signal, fs, 0.8, 2.5);

        assertTrue(quality2 > 0.75, "Expected high quality2 for pure sine");
        assertTrue(quality2 <= 1.0);
    }

    @Test
    void quality2_isLowForFlatSignal() {
        double[] signal = new double[900];

        double quality2 = SignalQualityScorer.quality2(signal, 30.0, 0.8, 2.5);

        assertEquals(0.0, quality2, 1e-12);
    }

    @Test
    void quality2_isLowerForNoisyBandSignal() {
        double fs = 30.0;
        int n = 900;
        double targetHz = 1.4;
        double[] pure = new double[n];
        for (int i = 0; i < n; i++) {
            double t = i / fs;
            pure[i] = Math.sin(2.0 * Math.PI * targetHz * t);
        }
        double pureQuality2 = SignalQualityScorer.quality2(pure, fs, 0.8, 2.5);

        double[] noisy = new double[n];
        double[] noisyFreqs = {
                0.82, 0.90, 0.98, 1.06, 1.14, 1.22, 1.30, 1.38, 1.46, 1.54,
                1.62, 1.70, 1.78, 1.86, 1.94, 2.02, 2.10, 2.18, 2.26, 2.34, 2.42
        };
        for (int i = 0; i < n; i++) {
            double t = i / fs;
            double v = 0.0;
            for (int j = 0; j < noisyFreqs.length; j++) {
                v += Math.sin(2.0 * Math.PI * noisyFreqs[j] * t + (j * 0.41));
            }
            noisy[i] = v / noisyFreqs.length;
        }
        double noisyQuality2 = SignalQualityScorer.quality2(noisy, fs, 0.8, 2.5);

        assertTrue(noisyQuality2 < pureQuality2, "Expected noisy quality2 to be lower than pure sine");
        assertTrue(noisyQuality2 < 0.55, "Expected noisy quality2 to remain low, actual=" + noisyQuality2);
    }
}
