package com.example.signal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PreprocessorBandPassTest {

    @Test
    void bandPass_keepsInBandAndAttenuatesOutOfBand() {
        double fs = 30.0;
        int n = 3000; // 100s, 0.01 Hz bin spacing

        double inBandHz = 1.20;
        double outBandLowHz = 0.30;
        double outBandHighHz = 4.50;

        double[] signal = new double[n];
        for (int i = 0; i < n; i++) {
            double t = i / fs;
            signal[i] = Math.sin(2.0 * Math.PI * inBandHz * t)
                    + Math.sin(2.0 * Math.PI * outBandLowHz * t)
                    + Math.sin(2.0 * Math.PI * outBandHighHz * t);
        }

        double[] filtered = Preprocessor.bandPass(signal, fs, 0.8, 2.5);

        double inAmp = amplitudeAt(signal, fs, inBandHz);
        double inAmpFiltered = amplitudeAt(filtered, fs, inBandHz);
        double lowAmp = amplitudeAt(filtered, fs, outBandLowHz);
        double highAmp = amplitudeAt(filtered, fs, outBandHighHz);

        assertTrue(inAmpFiltered > inAmp * 0.45, "In-band component should be preserved");
        assertTrue(lowAmp < inAmpFiltered * 0.35, "Low out-of-band component should be attenuated");
        assertTrue(highAmp < inAmpFiltered * 0.35, "High out-of-band component should be attenuated");
    }

    private static double amplitudeAt(double[] signal, double fs, double freqHz) {
        double sinSum = 0.0;
        double cosSum = 0.0;
        for (int i = 0; i < signal.length; i++) {
            double angle = 2.0 * Math.PI * freqHz * i / fs;
            sinSum += signal[i] * Math.sin(angle);
            cosSum += signal[i] * Math.cos(angle);
        }
        return 2.0 * Math.hypot(sinSum, cosSum) / signal.length;
    }
}
