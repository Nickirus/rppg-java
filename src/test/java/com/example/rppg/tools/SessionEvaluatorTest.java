package com.example.rppg.tools;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionEvaluatorTest {

    @Test
    void evaluate_computesExpectedMetricsFromGoldenCsv() throws Exception {
        Path csv = Path.of(getClass().getResource("/tools/session-golden.csv").toURI());

        SessionEvaluator.Metrics metrics = SessionEvaluator.evaluate(csv);

        assertEquals(12.0, metrics.durationSec(), 1e-9);
        assertEquals(11.0 / 13.0, metrics.validRatio(), 1e-9);
        assertEquals(12.0 / 13.0, metrics.posUsageRatio(), 1e-9);
        assertEquals(1.0 / 13.0, metrics.freezeRatio(), 1e-9);
        assertEquals(71.72727272727273, metrics.meanBpmValid(), 1e-9);
        assertEquals(0.8624393618641034, metrics.stdBpmValid(), 1e-9);
        assertEquals(0.0, metrics.jumpRate(), 1e-9);
        assertEquals(10.0, metrics.timeToStableSec(), 1e-9);
    }

    @Test
    void renderReport_containsTableFields() throws Exception {
        Path csv = Path.of(getClass().getResource("/tools/session-golden.csv").toURI());
        SessionEvaluator.Metrics metrics = SessionEvaluator.evaluate(csv);

        String report = SessionEvaluator.renderReport(csv, metrics);

        assertTrue(report.contains("Session Evaluator"));
        assertTrue(report.contains("durationSec"));
        assertTrue(report.contains("validRatio"));
        assertTrue(report.contains("timeToStableSec"));
    }
}
