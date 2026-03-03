package com.example.signal;

public final class SignalQualityScorer {
    private static final double EPS = 1e-12;
    private static final double DEFAULT_TEMPORAL_NORMALIZATION_EPS = 1e-6;

    private SignalQualityScorer() {
    }

    public static double peakDominance(double[] rawSignal, double fsHz, double minHz, double maxHz) {
        return peakDominance(rawSignal, fsHz, minHz, maxHz, true, DEFAULT_TEMPORAL_NORMALIZATION_EPS);
    }

    public static double peakDominance(
            double[] rawSignal,
            double fsHz,
            double minHz,
            double maxHz,
            boolean temporalNormalizationEnabled,
            double temporalNormalizationEps
    ) {
        Metrics metrics = analyze(rawSignal, fsHz, minHz, maxHz, temporalNormalizationEnabled, temporalNormalizationEps);
        return metrics.peakDominance();
    }

    public static double snr(double[] rawSignal, double fsHz, double minHz, double maxHz) {
        return snr(rawSignal, fsHz, minHz, maxHz, true, DEFAULT_TEMPORAL_NORMALIZATION_EPS);
    }

    public static double snr(
            double[] rawSignal,
            double fsHz,
            double minHz,
            double maxHz,
            boolean temporalNormalizationEnabled,
            double temporalNormalizationEps
    ) {
        Metrics metrics = analyze(rawSignal, fsHz, minHz, maxHz, temporalNormalizationEnabled, temporalNormalizationEps);
        return metrics.snr();
    }

    public static double quality(
            double[] rawSignal,
            double fsHz,
            double minHz,
            double maxHz,
            QualityMode mode,
            boolean temporalNormalizationEnabled,
            double temporalNormalizationEps
    ) {
        Metrics metrics = analyze(rawSignal, fsHz, minHz, maxHz, temporalNormalizationEnabled, temporalNormalizationEps);
        QualityMode resolved = mode == null ? QualityMode.SNR : mode;
        return resolved == QualityMode.PEAK_DOMINANCE ? metrics.peakDominance() : metrics.snr();
    }

    private static Metrics analyze(
            double[] rawSignal,
            double fsHz,
            double minHz,
            double maxHz,
            boolean temporalNormalizationEnabled,
            double temporalNormalizationEps
    ) {
        if (rawSignal == null || rawSignal.length < 3) {
            return Metrics.zero();
        }
        if (!Double.isFinite(fsHz) || fsHz <= 0.0 || !Double.isFinite(minHz) || !Double.isFinite(maxHz) || minHz <= 0.0 || maxHz <= minHz) {
            return Metrics.zero();
        }
        for (double sample : rawSignal) {
            if (!Double.isFinite(sample)) {
                return Metrics.zero();
            }
        }

        double[] x = rawSignal.clone();
        if (temporalNormalizationEnabled) {
            x = Preprocessor.temporalNormalizeByMean(x, temporalNormalizationEps);
        }
        x = Preprocessor.detrendAndNormalize(x);
        x = Preprocessor.bandPass(x, fsHz, minHz, maxHz);
        Preprocessor.applyHannWindowInPlace(x);
        double[] power = FftPowerSpectrum.powerSpectrum(x);

        int n = rawSignal.length;
        int kMin = (int) Math.ceil(minHz * n / fsHz);
        int kMax = (int) Math.floor(maxHz * n / fsHz);
        kMin = Math.max(1, kMin);
        kMax = Math.min(power.length - 1, kMax);
        if (kMin > kMax) {
            return Metrics.zero();
        }

        int peakIndex = PeakPicker.argMaxInBand(power, fsHz, minHz, maxHz);
        if (peakIndex < 0) {
            return Metrics.zero();
        }

        double peakPower = power[peakIndex];
        if (!Double.isFinite(peakPower) || peakPower <= EPS) {
            return Metrics.zero();
        }

        double totalBandPower = 0.0;
        double noisePowerSum = 0.0;
        int noiseBins = 0;
        for (int k = kMin; k <= kMax; k++) {
            double value = power[k];
            if (!Double.isFinite(value) || value <= 0.0) {
                continue;
            }
            totalBandPower += value;
            if (Math.abs(k - peakIndex) > 1) {
                noisePowerSum += value;
                noiseBins++;
            }
        }
        if (totalBandPower <= EPS) {
            return Metrics.zero();
        }

        double peakDominance = peakPower / totalBandPower;
        if (!Double.isFinite(peakDominance)) {
            peakDominance = 0.0;
        }
        peakDominance = Math.max(0.0, Math.min(1.0, peakDominance));

        if (noiseBins <= 0) {
            return new Metrics(peakDominance, 0.0);
        }

        double noiseMean = noisePowerSum / noiseBins;
        if (!Double.isFinite(noiseMean) || noiseMean <= EPS) {
            return new Metrics(peakDominance, 0.0);
        }
        double snr = peakPower / noiseMean;
        if (!Double.isFinite(snr) || snr < 0.0) {
            snr = 0.0;
        }
        return new Metrics(peakDominance, snr);
    }

    private record Metrics(double peakDominance, double snr) {
        private static Metrics zero() {
            return new Metrics(0.0, 0.0);
        }
    }
}
