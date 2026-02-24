package com.example.signal;

public final class Preprocessor {
    private Preprocessor() {}

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
}
