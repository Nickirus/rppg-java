package com.example.signal;

public final class SignalQualityScorer {
    private static final double EPS = 1e-12;

    private SignalQualityScorer() {
    }

    public static double peakDominance(double[] rawSignal, double fsHz, double minHz, double maxHz) {
        if (rawSignal == null || rawSignal.length < 3) {
            return 0.0;
        }
        if (!Double.isFinite(fsHz) || fsHz <= 0.0 || !Double.isFinite(minHz) || !Double.isFinite(maxHz) || minHz <= 0.0 || maxHz <= minHz) {
            return 0.0;
        }
        for (double sample : rawSignal) {
            if (!Double.isFinite(sample)) {
                return 0.0;
            }
        }

        double[] x = Preprocessor.detrendAndNormalize(rawSignal);
        Preprocessor.applyHannWindowInPlace(x);
        double[] power = FftPowerSpectrum.powerSpectrum(x);

        int n = rawSignal.length;
        int kMin = (int) Math.ceil(minHz * n / fsHz);
        int kMax = (int) Math.floor(maxHz * n / fsHz);
        kMin = Math.max(1, kMin);
        kMax = Math.min(power.length - 1, kMax);
        if (kMin > kMax) {
            return 0.0;
        }

        int peakIndex = PeakPicker.argMaxInBand(power, fsHz, minHz, maxHz);
        if (peakIndex < 0) {
            return 0.0;
        }
        double peakPower = power[peakIndex];
        if (!Double.isFinite(peakPower) || peakPower <= EPS) {
            return 0.0;
        }

        double totalBandPower = 0.0;
        for (int k = kMin; k <= kMax; k++) {
            double value = power[k];
            if (Double.isFinite(value) && value > 0.0) {
                totalBandPower += value;
            }
        }
        if (totalBandPower <= EPS) {
            return 0.0;
        }

        double score = peakPower / totalBandPower;
        if (!Double.isFinite(score)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, score));
    }
}
