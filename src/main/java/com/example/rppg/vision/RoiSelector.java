package com.example.rppg.vision;

public final class RoiSelector {
    private RoiSelector() {
    }

    public static FaceTracker.Rect foreheadRoi(FaceTracker.Rect faceRect) {
        int x = faceRect.x();
        int y = faceRect.y();
        int w = faceRect.width();
        int h = faceRect.height();

        int roiX = x + (int) (0.2 * w);
        int roiY = y + (int) (0.15 * h);
        int roiW = (int) (0.6 * w);
        int roiH = (int) (0.2 * h);

        return new FaceTracker.Rect(roiX, roiY, roiW, roiH);
    }
}