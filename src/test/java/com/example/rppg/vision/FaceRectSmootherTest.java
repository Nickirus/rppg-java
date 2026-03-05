package com.example.rppg.vision;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FaceRectSmootherTest {

    @Test
    void smoothing_reducesCenterVariance_andStaysWithinFrameBounds() {
        FaceRectSmoother smoother = new FaceRectSmoother(0.2, 0.25);
        int frameW = 640;
        int frameH = 480;

        List<Double> rawCx = new ArrayList<>();
        List<Double> rawCy = new ArrayList<>();
        List<Double> smoothCx = new ArrayList<>();
        List<Double> smoothCy = new ArrayList<>();

        int[] jitter = {0, 4, -3, 5, -4, 3, -2, 4, -5, 2, -3, 5, -4, 3, -2, 4, -5, 2, -3, 5};
        for (int i = 0; i < 40; i++) {
            int jx = jitter[i % jitter.length];
            int jy = jitter[(i * 3) % jitter.length];
            int jw = jitter[(i * 5) % jitter.length];
            int jh = jitter[(i * 7) % jitter.length];

            FaceTracker.Rect raw = new FaceTracker.Rect(100 + jx, 80 + jy, 120 + jw, 140 + jh);
            FaceRectSmoother.Result result = smoother.update(raw, frameW, frameH);
            FaceTracker.Rect smoothed = result.smoothedRect();

            rawCx.add(raw.x() + raw.width() / 2.0);
            rawCy.add(raw.y() + raw.height() / 2.0);
            smoothCx.add(smoothed.x() + smoothed.width() / 2.0);
            smoothCy.add(smoothed.y() + smoothed.height() / 2.0);

            assertTrue(smoothed.x() >= 0 && smoothed.y() >= 0);
            assertTrue(smoothed.width() > 0 && smoothed.height() > 0);
            assertTrue(smoothed.x() + smoothed.width() <= frameW);
            assertTrue(smoothed.y() + smoothed.height() <= frameH);
        }

        double rawVarX = variance(rawCx);
        double rawVarY = variance(rawCy);
        double smoothVarX = variance(smoothCx);
        double smoothVarY = variance(smoothCy);

        assertTrue(smoothVarX < rawVarX, "Expected smoother to reduce X variance");
        assertTrue(smoothVarY < rawVarY, "Expected smoother to reduce Y variance");
    }

    private static double variance(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double acc = 0.0;
        for (double v : values) {
            double d = v - mean;
            acc += d * d;
        }
        return values.isEmpty() ? 0.0 : acc / values.size();
    }
}
