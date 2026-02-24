package com.example.signal;

/**
 * MVP-FFT без внешних зависимостей: DFT O(N^2) (для окна 30s*30fps=900 точек терпимо).
 * Позже можно заменить на JTransforms/Apache Commons Math FFT.
 */
public final class FftPowerSpectrum {
    private FftPowerSpectrum() {}

    public static double[] powerSpectrum(double[] x) {
        int n = x.length;
        int half = n / 2;
        double[] p = new double[half + 1];

        for (int k = 0; k <= half; k++) {
            double re = 0.0;
            double im = 0.0;
            for (int t = 0; t < n; t++) {
                double angle = -2.0 * Math.PI * k * t / n;
                re += x[t] * Math.cos(angle);
                im += x[t] * Math.sin(angle);
            }
            p[k] = re * re + im * im;
        }
        return p;
    }
}
