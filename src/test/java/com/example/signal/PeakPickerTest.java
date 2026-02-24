package com.example.signal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PeakPickerTest {

    @Test
    void argMaxInBand_picksPeakFromSyntheticSineSpectrum() {
        double fs = 30.0;
        int n = 900;
        double freqHz = 1.5; // 90 bpm

        double[] signal = new double[n];
        for (int i = 0; i < n; i++) {
            double t = i / fs;
            signal[i] = Math.sin(2.0 * Math.PI * freqHz * t);
        }

        double[] power = FftPowerSpectrum.powerSpectrum(signal);
        int best = PeakPicker.argMaxInBand(power, fs, 0.8, 2.5);

        int expectedBin = (int) Math.round(freqHz * n / fs);
        assertEquals(expectedBin, best);
    }
}