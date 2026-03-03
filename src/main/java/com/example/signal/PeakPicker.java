package com.example.signal;

public final class PeakPicker {
    private PeakPicker() {}

    public static int argMaxInBand(double[] power, double fsHz, double minHz, double maxHz) {
        int n = (power.length - 1) * 2; // because power length is half+1
        int kMin = (int) Math.ceil(minHz * n / fsHz);
        int kMax = (int) Math.floor(maxHz * n / fsHz);

        kMin = Math.max(kMin, 1);
        kMax = Math.min(kMax, power.length - 1);
        if (kMin > kMax) return -1;

        int bestK = kMin;
        double best = power[kMin];
        for (int k = kMin + 1; k <= kMax; k++) {
            if (power[k] > best) {
                best = power[k];
                bestK = k;
            }
        }
        return bestK;
    }

    public static double binToHz(int k, int n, double fsHz) {
        return (k * fsHz) / n;
    }

    public static double binToHz(double k, int n, double fsHz) {
        return (k * fsHz) / n;
    }

    public static double refineParabolicBin(double[] power, int kPeak) {
        if (power == null || kPeak <= 0 || kPeak >= power.length - 1) {
            return kPeak;
        }

        double left = power[kPeak - 1];
        double center = power[kPeak];
        double right = power[kPeak + 1];
        if (!Double.isFinite(left) || !Double.isFinite(center) || !Double.isFinite(right)) {
            return kPeak;
        }

        double denominator = left - (2.0 * center) + right;
        if (Math.abs(denominator) < 1e-12) {
            return kPeak;
        }

        double delta = 0.5 * (left - right) / denominator;
        if (!Double.isFinite(delta)) {
            return kPeak;
        }
        delta = Math.max(-0.5, Math.min(0.5, delta));
        return kPeak + delta;
    }
}
