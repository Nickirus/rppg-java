package com.example.rppg.vision;

public final class SkinMasker {
    private SkinMasker() {
    }

    public static double estimateSkinCoverage(FaceTracker.Frame frame, FaceTracker.Rect roi) {
        if (frame.width() <= 0 || frame.height() <= 0 || roi.width() <= 0 || roi.height() <= 0) {
            return 0.0;
        }
        return 1.0;
    }
}