package com.example.signal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FftPowerSpectrumTest {

    @Test
    void fftPeakDetection_remainsCorrectForKnownSine() {
        double fs = 30.0;
        int n = 900;
        double freqHz = 1.50; // 90 bpm

        double[] signal = new double[n];
        for (int i = 0; i < n; i++) {
            signal[i] = Math.sin(2.0 * Math.PI * freqHz * i / fs);
        }

        double[] power = FftPowerSpectrum.powerSpectrum(signal);
        int peak = PeakPicker.argMaxInBand(power, fs, 0.8, 2.5);

        int expectedBin = (int) Math.round(freqHz * n / fs);
        assertEquals(expectedBin, peak);
    }
}
