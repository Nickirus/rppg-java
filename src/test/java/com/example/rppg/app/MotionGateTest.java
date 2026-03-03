package com.example.rppg.app;

import com.example.rppg.vision.FaceTracker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotionGateTest {

    @Test
    void motionScore_usesMaxOfCenterShiftAndAreaChange() {
        FaceTracker.Rect previous = new FaceTracker.Rect(0, 0, 100, 120);
        FaceTracker.Rect shifted = new FaceTracker.Rect(10, 0, 100, 120);
        FaceTracker.Rect areaChanged = new FaceTracker.Rect(10, 0, 120, 120);

        double shiftOnly = MotionGate.motionScore(previous, shifted);
        double areaDominant = MotionGate.motionScore(previous, areaChanged);

        assertEquals(10.0 / 120.0, shiftOnly, 1e-9);
        assertEquals(0.2, areaDominant, 1e-9);
    }

    @Test
    void entersFreezeOnlyAfterSustainedMotion() {
        MotionGate gate = new MotionGate(0.10, 300L, 3_000L);
        FaceTracker.Rect face0 = faceAt(0);
        FaceTracker.Rect face1 = faceAt(40);
        FaceTracker.Rect face2 = faceAt(80);
        FaceTracker.Rect face3 = faceAt(120);

        MotionGate.State s0 = gate.update(face0, ns(0));
        MotionGate.State s1 = gate.update(face1, ns(100));
        MotionGate.State s2 = gate.update(face2, ns(350));
        MotionGate.State s3 = gate.update(face3, ns(450));

        assertFalse(s0.frozen());
        assertFalse(s1.frozen());
        assertFalse(s2.frozen());
        assertTrue(s3.frozen());
    }

    @Test
    void sustainedFreezeTriggersSingleResetEvent() {
        MotionGate gate = new MotionGate(0.10, 300L, 3_000L);

        gate.update(faceAt(0), ns(0));
        gate.update(faceAt(40), ns(100));
        gate.update(faceAt(80), ns(450)); // enters freeze

        MotionGate.State beforeReset = gate.update(faceAt(120), ns(3_300));
        MotionGate.State resetNow = gate.update(faceAt(160), ns(3_500));
        MotionGate.State afterResetEvent = gate.update(faceAt(200), ns(3_800));

        assertTrue(beforeReset.frozen());
        assertFalse(beforeReset.shouldResetWindow());
        assertTrue(resetNow.frozen());
        assertTrue(resetNow.shouldResetWindow());
        assertTrue(afterResetEvent.frozen());
        assertFalse(afterResetEvent.shouldResetWindow());
    }

    @Test
    void lowMotionExitsFreezeAndClearsState() {
        MotionGate gate = new MotionGate(0.10, 300L, 3_000L);

        gate.update(faceAt(0), ns(0));
        gate.update(faceAt(40), ns(100));
        MotionGate.State frozen = gate.update(faceAt(80), ns(450));
        MotionGate.State cleared = gate.update(faceAt(80), ns(700));

        assertTrue(frozen.frozen());
        assertFalse(cleared.frozen());
        assertEquals(0.0, cleared.motionScore(), 1e-9);
    }

    private static FaceTracker.Rect faceAt(int x) {
        return new FaceTracker.Rect(x, 0, 100, 100);
    }

    private static long ns(long millis) {
        return millis * 1_000_000L;
    }
}
