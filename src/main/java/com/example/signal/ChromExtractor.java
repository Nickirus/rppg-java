package com.example.signal;

import java.util.ArrayDeque;
import java.util.Deque;

public final class ChromExtractor implements RppgSignalExtractor {
    private static final double EPS = 1e-9;

    private final int temporalWindow;
    private final Deque<RoiStats> window = new ArrayDeque<>();

    public ChromExtractor(int temporalWindow) {
        if (temporalWindow < 8) {
            throw new IllegalArgumentException("temporalWindow must be >= 8");
        }
        this.temporalWindow = temporalWindow;
    }

    @Override
    public double extract(RoiStats roiStats) {
        if (window.size() == temporalWindow) {
            window.removeFirst();
        }
        window.addLast(roiStats);
        if (window.size() < 3) {
            return 0.0;
        }

        int n = window.size();
        double[] rn = new double[n];
        double[] gn = new double[n];
        double[] bn = new double[n];

        double meanR = 0.0;
        double meanG = 0.0;
        double meanB = 0.0;
        int i = 0;
        for (RoiStats stats : window) {
            rn[i] = stats.meanR();
            gn[i] = stats.meanG();
            bn[i] = stats.meanB();
            meanR += rn[i];
            meanG += gn[i];
            meanB += bn[i];
            i++;
        }
        meanR = meanR / n;
        meanG = meanG / n;
        meanB = meanB / n;

        double[] x = new double[n];
        double[] y = new double[n];
        for (i = 0; i < n; i++) {
            double rHat = normalize(rn[i], meanR);
            double gHat = normalize(gn[i], meanG);
            double bHat = normalize(bn[i], meanB);
            x[i] = 3.0 * rHat - 2.0 * gHat;
            y[i] = 1.5 * rHat + gHat - 1.5 * bHat;
        }
        double alpha = safeStd(x) / Math.max(safeStd(y), EPS);
        return x[n - 1] - alpha * y[n - 1];
    }

    @Override
    public void reset() {
        window.clear();
    }

    private static double normalize(double value, double mean) {
        if (!Double.isFinite(mean) || Math.abs(mean) < EPS) {
            return 0.0;
        }
        return (value / mean) - 1.0;
    }

    private static double safeStd(double[] values) {
        int n = values.length;
        if (n < 2) {
            return 0.0;
        }
        double mean = 0.0;
        for (double value : values) {
            mean += value;
        }
        mean /= n;
        double var = 0.0;
        for (double value : values) {
            double d = value - mean;
            var += d * d;
        }
        return Math.sqrt(var / n);
    }
}
