package com.example.signal;

public final class IlluminationCompensator {
    private final double[] xBuffer;
    private final double[] bgBuffer;

    private int size;
    private int head;

    private double sumX;
    private double sumBg;
    private double sumBg2;
    private double sumXBg;

    public IlluminationCompensator(int windowSamples) {
        if (windowSamples < 3) {
            throw new IllegalArgumentException("windowSamples must be >= 3");
        }
        this.xBuffer = new double[windowSamples];
        this.bgBuffer = new double[windowSamples];
    }

    public Result update(double x, double bg) {
        if (!Double.isFinite(x) || !Double.isFinite(bg)) {
            return new Result(x, 0.0);
        }

        if (size == xBuffer.length) {
            double oldX = xBuffer[head];
            double oldBg = bgBuffer[head];
            sumX -= oldX;
            sumBg -= oldBg;
            sumBg2 -= oldBg * oldBg;
            sumXBg -= oldX * oldBg;
        } else {
            size++;
        }

        xBuffer[head] = x;
        bgBuffer[head] = bg;
        head = (head + 1) % xBuffer.length;

        sumX += x;
        sumBg += bg;
        sumBg2 += bg * bg;
        sumXBg += x * bg;

        double beta = estimateBeta();
        double compensated = x - beta * bg;
        if (!Double.isFinite(compensated)) {
            compensated = x;
        }
        return new Result(compensated, beta);
    }

    public void reset() {
        size = 0;
        head = 0;
        sumX = 0.0;
        sumBg = 0.0;
        sumBg2 = 0.0;
        sumXBg = 0.0;
    }

    private double estimateBeta() {
        if (size < 3) {
            return 0.0;
        }
        double n = size;
        double cov = sumXBg - (sumX * sumBg / n);
        double varBg = sumBg2 - (sumBg * sumBg / n);
        if (!Double.isFinite(varBg) || varBg <= 1e-12) {
            return 0.0;
        }
        double beta = cov / varBg;
        if (!Double.isFinite(beta)) {
            return 0.0;
        }
        return beta;
    }

    public record Result(double compensatedSample, double regressionCoefficient) {
    }
}
