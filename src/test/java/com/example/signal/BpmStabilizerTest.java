package com.example.signal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BpmStabilizerTest {

    @Test
    void holdsLastGoodBpmWhenQualityDrops() {
        BpmStabilizer stabilizer = new BpmStabilizer(8.0);

        BpmStabilizer.Decision first = stabilizer.update(
                HeartRateEstimator.Result.valid(78.0, 1.30, 13),
                0.45,
                0.20
        );
        BpmStabilizer.Decision second = stabilizer.update(
                HeartRateEstimator.Result.valid(102.0, 1.70, 17),
                0.05,
                0.20
        );

        assertEquals(BpmStatus.VALID, first.status());
        assertEquals(78.0, first.bpm(), 1e-9);
        assertEquals(BpmStatus.HOLDING, second.status());
        assertEquals(78.0, second.bpm(), 1e-9, "Low quality must keep the last good BPM");
    }

    @Test
    void clampsLargeBpmJumpPerUpdate() {
        BpmStabilizer stabilizer = new BpmStabilizer(6.0);

        BpmStabilizer.Decision first = stabilizer.update(
                HeartRateEstimator.Result.valid(70.0, 1.1667, 12),
                0.50,
                0.20
        );
        BpmStabilizer.Decision second = stabilizer.update(
                HeartRateEstimator.Result.valid(95.0, 1.5833, 16),
                0.52,
                0.20
        );

        assertEquals(BpmStatus.VALID, first.status());
        assertEquals(BpmStatus.HOLDING, second.status(), "Step-limited update is reported as HOLDING");
        assertEquals(76.0, second.bpm(), 1e-9, "BPM jump must be clamped by maxStepPerUpdate");
        assertEquals(95.0, second.rawBpm(), 1e-9, "Raw estimate should still be available");
    }
}
