package com.example.signal;

public class HeartRateEstimator {

    private final double fsHz;
    private final double minHz;
    private final double maxHz;

    public HeartRateEstimator(double fsHz, double minHz, double maxHz) {
        this.fsHz = fsHz;
        this.minHz = minHz;
        this.maxHz = maxHz;
    }

    public Result estimate(double[] rawSignal) {
        double[] x = Preprocessor.detrendAndNormalize(rawSignal);
        Preprocessor.applyHannWindowInPlace(x);

        double[] p = FftPowerSpectrum.powerSpectrum(x);
        int n = rawSignal.length;
        int k = PeakPicker.argMaxInBand(p, fsHz, minHz, maxHz);
        if (k < 0) return Result.invalid("No peak in band");

        double hz = PeakPicker.binToHz(k, n, fsHz);
        double bpm = hz * 60.0;
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
