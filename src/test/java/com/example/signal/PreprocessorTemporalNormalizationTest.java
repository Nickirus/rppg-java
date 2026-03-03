package com.example.signal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreprocessorTemporalNormalizationTest {

    @Test
    void temporalNormalizeByMean_appliesExpectedFormula() {
        double[] x = {10.0, 11.0, 9.0};

        double[] y = Preprocessor.temporalNormalizeByMean(x, 1e-6);

        assertEquals(0.0, y[0], 1e-12);
        assertEquals(0.1, y[1], 1e-12);
        assertEquals(-0.1, y[2], 1e-12);
    }

    @Test
    void temporalNormalizeByMean_usesEpsWhenMeanIsTooSmall() {
        double[] x = {1e-9, 2e-9, 3e-9};

        double[] y = Preprocessor.temporalNormalizeByMean(x, 1e-6);

        assertEquals(-0.001, y[0], 1e-12);
        assertEquals(0.0, y[1], 1e-12);
        assertEquals(0.001, y[2], 1e-12);
    }
}
