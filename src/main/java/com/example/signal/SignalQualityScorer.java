package com.example.signal;

public final class SignalQualityScorer {
    private static final double EPS = 1e-12;
    private static final double DEFAULT_TEMPORAL_NORMALIZATION_EPS = 1e-6;
    private static final Quality2Config DEFAULT_QUALITY2_CONFIG = Quality2Config.defaults();

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
        return quality(
                rawSignal,
                fsHz,
                minHz,
                maxHz,
                mode,
                temporalNormalizationEnabled,
                temporalNormalizationEps,
                DEFAULT_QUALITY2_CONFIG
        );
    }

    public static double quality(
            double[] rawSignal,
            double fsHz,
            double minHz,
            double maxHz,
            QualityMode mode,
            boolean temporalNormalizationEnabled,
            double temporalNormalizationEps,
            Quality2Config quality2Config
    ) {
        Metrics metrics = analyze(rawSignal, fsHz, minHz, maxHz, temporalNormalizationEnabled, temporalNormalizationEps);
        QualityMode resolved = mode == null ? QualityMode.SNR : mode;
        return switch (resolved) {
            case PEAK_DOMINANCE -> metrics.peakDominance();
            case QUALITY2 -> quality2FromMetrics(metrics, quality2Config);
            case SNR -> metrics.snr();
        };
    }

    public static double quality2(double[] rawSignal, double fsHz, double minHz, double maxHz) {
        return quality2(rawSignal, fsHz, minHz, maxHz, true, DEFAULT_TEMPORAL_NORMALIZATION_EPS, DEFAULT_QUALITY2_CONFIG);
    }

    public static double quality2(
            double[] rawSignal,
            double fsHz,
            double minHz,
            double maxHz,
            boolean temporalNormalizationEnabled,
            double temporalNormalizationEps,
            Quality2Config quality2Config
    ) {
        Metrics metrics = analyze(rawSignal, fsHz, minHz, maxHz, temporalNormalizationEnabled, temporalNormalizationEps);
        return quality2FromMetrics(metrics, quality2Config);
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
        double secondPeakPower = 0.0;
        for (int k = kMin; k <= kMax; k++) {
            double value = power[k];
            if (!Double.isFinite(value) || value <= 0.0) {
                continue;
            }
            totalBandPower += value;
            if (Math.abs(k - peakIndex) > 1) {
                noisePowerSum += value;
                noiseBins++;
                if (value > secondPeakPower) {
                    secondPeakPower = value;
                }
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

        double noiseFloor = noiseBins <= 0 ? 0.0 : noisePowerSum / noiseBins;
        double snr = peakPower / Math.max(EPS, noiseFloor);
        if (!Double.isFinite(snr) || snr < 0.0) {
            snr = 0.0;
        }

        double margin = peakPower / Math.max(EPS, secondPeakPower);
        if (!Double.isFinite(margin) || margin < 0.0) {
            margin = 0.0;
        }

        double harmonicRatio = harmonicRatio(power, peakIndex, peakPower);
        return new Metrics(peakDominance, snr, margin, harmonicRatio);
    }

    private static double harmonicRatio(double[] power, int peakIndex, double peakPower) {
        int harmonicBin = peakIndex * 2;
        if (harmonicBin <= 0 || harmonicBin >= power.length) {
            return Double.NaN;
        }
        int kStart = Math.max(1, harmonicBin - 1);
        int kEnd = Math.min(power.length - 1, harmonicBin + 1);
        double harmonicPower = 0.0;
        for (int k = kStart; k <= kEnd; k++) {
            double value = power[k];
            if (Double.isFinite(value) && value > harmonicPower) {
                harmonicPower = value;
            }
        }
        if (harmonicPower <= 0.0 || !Double.isFinite(peakPower) || peakPower <= EPS) {
            return 0.0;
        }
        return harmonicPower / peakPower;
    }

    private static double quality2FromMetrics(Metrics metrics, Quality2Config quality2Config) {
        Quality2Config cfg = quality2Config == null ? DEFAULT_QUALITY2_CONFIG : quality2Config;
        double snrScore = linearScore(metrics.snr(), cfg.snrLow(), cfg.snrHigh());
        double marginScore = linearScore(metrics.margin(), cfg.marginLow(), cfg.marginHigh());

        double harmonicWeight = cfg.harmonicEnabled() ? Math.max(0.0, cfg.harmonicWeight()) : 0.0;
        double harmonicScore = 0.5;
        if (cfg.harmonicEnabled() && Double.isFinite(metrics.harmonicRatio())) {
            harmonicScore = linearScore(metrics.harmonicRatio(), cfg.harmonicLow(), cfg.harmonicHigh());
        }

        double snrWeight = Math.max(0.0, cfg.snrWeight());
        double marginWeight = Math.max(0.0, cfg.marginWeight());
        double weightSum = snrWeight + marginWeight + harmonicWeight;
        if (weightSum <= EPS) {
            return 0.0;
        }

        double value = (snrWeight * snrScore + marginWeight * marginScore + harmonicWeight * harmonicScore) / weightSum;
        return clamp01(value);
    }

    private static double linearScore(double value, double low, double high) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        if (!Double.isFinite(low) || !Double.isFinite(high)) {
            return 0.0;
        }
        if (high <= low) {
            return value >= high ? 1.0 : 0.0;
        }
        return clamp01((value - low) / (high - low));
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        if (value <= 0.0) {
            return 0.0;
        }
        if (value >= 1.0) {
            return 1.0;
        }
        return value;
    }

    public record Quality2Config(
            double snrLow,
            double snrHigh,
            double marginLow,
            double marginHigh,
            boolean harmonicEnabled,
            double harmonicLow,
            double harmonicHigh,
            double snrWeight,
            double marginWeight,
            double harmonicWeight
    ) {
        public Quality2Config {
            if (!Double.isFinite(snrLow) || !Double.isFinite(snrHigh)) {
                throw new IllegalArgumentException("snr thresholds must be finite");
            }
            if (!Double.isFinite(marginLow) || !Double.isFinite(marginHigh)) {
                throw new IllegalArgumentException("margin thresholds must be finite");
            }
            if (!Double.isFinite(harmonicLow) || !Double.isFinite(harmonicHigh)) {
                throw new IllegalArgumentException("harmonic thresholds must be finite");
            }
            if (!Double.isFinite(snrWeight) || snrWeight < 0.0) {
                throw new IllegalArgumentException("snrWeight must be >= 0");
            }
            if (!Double.isFinite(marginWeight) || marginWeight < 0.0) {
                throw new IllegalArgumentException("marginWeight must be >= 0");
            }
            if (!Double.isFinite(harmonicWeight) || harmonicWeight < 0.0) {
                throw new IllegalArgumentException("harmonicWeight must be >= 0");
            }
            if (snrWeight + marginWeight + harmonicWeight <= 0.0) {
                throw new IllegalArgumentException("at least one quality2 weight must be > 0");
            }
        }

        public static Quality2Config defaults() {
            return new Quality2Config(
                    2.0,
                    8.0,
                    1.1,
                    2.5,
                    true,
                    0.05,
                    0.35,
                    0.6,
                    0.3,
                    0.1
            );
        }
    }

    private record Metrics(double peakDominance, double snr, double margin, double harmonicRatio) {
        private static Metrics zero() {
            return new Metrics(0.0, 0.0, 0.0, 0.0);
        }
    }
}
