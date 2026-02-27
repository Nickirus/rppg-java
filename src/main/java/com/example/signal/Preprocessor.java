package com.example.signal;

public final class Preprocessor {
    private Preprocessor() {
    }

    public static double[] detrendAndNormalize(double[] x) {
        int n = x.length;
        double mean = 0.0;
        for (double v : x) mean += v;
        mean /= n;

        double[] y = new double[n];
        for (int i = 0; i < n; i++) y[i] = x[i] - mean;

        // простая нормировка по std
        double var = 0.0;
        for (double v : y) var += v * v;
        var /= n;
        double std = Math.sqrt(var);
        if (std < 1e-9) return y;

        for (int i = 0; i < n; i++) y[i] /= std;
        return y;
    }

    public static void applyHannWindowInPlace(double[] x) {
        int n = x.length;
        if (n <= 1) return;
        for (int i = 0; i < n; i++) {
            double w = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (n - 1)));
            x[i] *= w;
        }
    }

    public static double[] bandPass(double[] x, double fsHz, double lowCutHz, double highCutHz) {
        if (x == null) {
            return null;
        }
        if (!Double.isFinite(fsHz) || fsHz <= 0.0
                || !Double.isFinite(lowCutHz) || !Double.isFinite(highCutHz)
                || lowCutHz <= 0.0 || highCutHz <= lowCutHz) {
            return x.clone();
        }

        double[] y = x.clone();
        // Two RC stages provide basic attenuation while staying deterministic and cheap.
        for (int i = 0; i < 2; i++) {
            y = highPassRc(y, fsHz, lowCutHz);
            y = lowPassRc(y, fsHz, highCutHz);
        }
        return y;
    }

    private static double[] highPassRc(double[] x, double fsHz, double cutoffHz) {
        int n = x.length;
        double[] y = new double[n];
        if (n == 0) {
            return y;
        }

        double dt = 1.0 / fsHz;
        double rc = 1.0 / (2.0 * Math.PI * cutoffHz);
        double alpha = rc / (rc + dt);
        y[0] = 0.0;
        for (int i = 1; i < n; i++) {
            y[i] = alpha * (y[i - 1] + x[i] - x[i - 1]);
        }
        return y;
    }

    private static double[] lowPassRc(double[] x, double fsHz, double cutoffHz) {
        int n = x.length;
        double[] y = new double[n];
        if (n == 0) {
            return y;
        }

        double dt = 1.0 / fsHz;
        double rc = 1.0 / (2.0 * Math.PI * cutoffHz);
        double alpha = dt / (rc + dt);
        y[0] = x[0];
        for (int i = 1; i < n; i++) {
            y[i] = y[i - 1] + alpha * (x[i] - y[i - 1]);
        }
        return y;
    }
}
