package com.example.signal;

import org.jtransforms.fft.DoubleFFT_1D;

public final class FftPowerSpectrum {
    private FftPowerSpectrum() {
    }

    public static double[] powerSpectrum(double[] x) {
        int n = x.length;
        if (n == 0) {
            return new double[0];
        }

        int half = n / 2;
        double[] p = new double[half + 1];
        double[] fftBuffer = new double[2 * n];
        System.arraycopy(x, 0, fftBuffer, 0, n);

        DoubleFFT_1D fft = new DoubleFFT_1D(n);
        fft.realForwardFull(fftBuffer);

        for (int k = 0; k <= half; k++) {
            double re = fftBuffer[2 * k];
            double im = fftBuffer[2 * k + 1];
            p[k] = re * re + im * im;
        }
        return p;
    }
}
