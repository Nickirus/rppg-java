package com.example.signal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RppgSignalExtractorsTest {

    @Test
    void greenExtractorProducesNonFlatOutputWhenGreenVaries() {
        GreenExtractor extractor = new GreenExtractor();
        double[] output = runExtractor(
                extractor,
                240,
                30.0,
                1.2,
                0.0,
                2.0,
                0.0
        );
        assertTrue(std(output, 16) > 1e-6, "GREEN output should vary when meanG varies");
    }

    @Test
    void posExtractorProducesNonFlatOutputWhenOnlyRedVaries() {
        PosExtractor extractor = new PosExtractor(32);
        double[] output = runExtractor(
                extractor,
                240,
                30.0,
                1.1,
                2.0,
                0.0,
                0.0
        );
        assertTrue(std(output, 40) > 1e-6, "POS output should vary for red-channel sinusoid");
    }

    @Test
    void chromExtractorProducesNonFlatOutputWhenOnlyGreenVaries() {
        ChromExtractor extractor = new ChromExtractor(32);
        double[] output = runExtractor(
                extractor,
                240,
                30.0,
                1.1,
                0.0,
                2.0,
                0.0
        );
        assertTrue(std(output, 40) > 1e-6, "CHROM output should vary for green-channel sinusoid");
    }

    private static double[] runExtractor(
            RppgSignalExtractor extractor,
            int n,
            double fs,
            double freqHz,
            double ampR,
            double ampG,
            double ampB
    ) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double t = i / fs;
            double s = Math.sin(2.0 * Math.PI * freqHz * t);
            RoiStats stats = new RoiStats(
                    120.0 + ampR * s,
                    130.0 + ampG * s,
                    110.0 + ampB * s
            );
            out[i] = extractor.extract(stats);
        }
        return out;
    }

    private static double std(double[] values, int skip) {
        int start = Math.max(0, Math.min(skip, values.length));
        int n = values.length - start;
        if (n < 2) {
            return 0.0;
        }
        double mean = 0.0;
        for (int i = start; i < values.length; i++) {
            mean += values[i];
        }
        mean /= n;
        double var = 0.0;
        for (int i = start; i < values.length; i++) {
            double d = values[i] - mean;
            var += d * d;
        }
        return Math.sqrt(var / n);
    }
}
