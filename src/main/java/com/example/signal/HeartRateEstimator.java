package com.example.signal;

public class HeartRateEstimator {

    private static final double FLAT_SIGNAL_ENERGY_EPS = 1e-9;
    private static final double MIN_PEAK_POWER_EPS = 1e-12;

    private final double fsHz;
    private final double minHz;
    private final double maxHz;
    private final boolean temporalNormalizationEnabled;
    private final double temporalNormalizationEps;

    public HeartRateEstimator(double fsHz, double minHz, double maxHz) {
        this(fsHz, minHz, maxHz, true, 1e-6);
    }

    public HeartRateEstimator(
            double fsHz,
            double minHz,
            double maxHz,
            boolean temporalNormalizationEnabled,
            double temporalNormalizationEps
    ) {
        if (!Double.isFinite(fsHz) || fsHz <= 0.0) {
            throw new IllegalArgumentException("fsHz must be > 0");
        }
        if (!Double.isFinite(minHz) || !Double.isFinite(maxHz) || minHz <= 0.0 || maxHz <= minHz) {
            throw new IllegalArgumentException("invalid HR band");
        }
        this.fsHz = fsHz;
        this.minHz = minHz;
        this.maxHz = maxHz;
        this.temporalNormalizationEnabled = temporalNormalizationEnabled;
        this.temporalNormalizationEps = temporalNormalizationEps;
    }

    public Result estimate(double[] rawSignal) {
        if (rawSignal == null || rawSignal.length < 3) {
            return Result.invalid("Signal is too short");
        }
        for (double sample : rawSignal) {
            if (!Double.isFinite(sample)) {
                return Result.invalid("Signal contains non-finite values");
            }
        }

        double[] x = rawSignal.clone();
        if (temporalNormalizationEnabled) {
            x = Preprocessor.temporalNormalizeByMean(x, temporalNormalizationEps);
        }
        x = Preprocessor.detrendAndNormalize(x);
        x = Preprocessor.bandPass(x, fsHz, minHz, maxHz);
        double signalEnergy = 0.0;
        for (double v : x) {
            signalEnergy += v * v;
        }
        if (signalEnergy < FLAT_SIGNAL_ENERGY_EPS) {
            return Result.invalid("Signal is flat");
        }
        Preprocessor.applyHannWindowInPlace(x);

        double[] p = FftPowerSpectrum.powerSpectrum(x);
        int n = rawSignal.length;
        int k = PeakPicker.argMaxInBand(p, fsHz, minHz, maxHz);
        if (k < 0) {
            return Result.invalid("No peak in band");
        }
        double peakPower = p[k];
        if (!Double.isFinite(peakPower) || peakPower <= MIN_PEAK_POWER_EPS) {
            return Result.invalid("Peak power too low");
        }

        double hz = PeakPicker.binToHz(k, n, fsHz);
        if (!Double.isFinite(hz) || hz < minHz || hz > maxHz) {
            return Result.invalid("Peak frequency out of band");
        }
        double bpm = hz * 60.0;
        if (!Double.isFinite(bpm)) {
            return Result.invalid("BPM is invalid");
        }
        return Result.valid(bpm, hz, k);
    }

    public record Result(boolean valid, double bpm, double hz, int bin, String reason) {
        public static Result valid(double bpm, double hz, int bin) {
            return new Result(true, bpm, hz, bin, null);
        }
        public static Result invalid(String reason) {
            return new Result(false, Double.NaN, Double.NaN, -1, reason);
        }
    }
}
