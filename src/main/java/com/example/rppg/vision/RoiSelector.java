package com.example.rppg.vision;

public final class RoiSelector {
    private RoiSelector() {
    }

    public static FaceTracker.Rect foreheadRoi(FaceTracker.Rect faceRect) {
        return proportionalRect(faceRect, 0.20, 0.15, 0.60, 0.20);
    }

    public static MultiRoi multiRegionRois(FaceTracker.Rect faceRect) {
        FaceTracker.Rect forehead = foreheadRoi(faceRect);
        FaceTracker.Rect leftCheek = proportionalRect(faceRect, 0.14, 0.48, 0.28, 0.22);
        FaceTracker.Rect rightCheek = proportionalRect(faceRect, 0.58, 0.48, 0.28, 0.22);
        return new MultiRoi(forehead, leftCheek, rightCheek);
    }

    private static FaceTracker.Rect proportionalRect(
            FaceTracker.Rect base,
            double xOffset,
            double yOffset,
            double widthRatio,
            double heightRatio
    ) {
        int x = base.x() + (int) Math.round(base.width() * xOffset);
        int y = base.y() + (int) Math.round(base.height() * yOffset);
        int w = Math.max(1, (int) Math.round(base.width() * widthRatio));
        int h = Math.max(1, (int) Math.round(base.height() * heightRatio));
        return new FaceTracker.Rect(x, y, w, h);
    }

    public record MultiRoi(
            FaceTracker.Rect forehead,
            FaceTracker.Rect leftCheek,
            FaceTracker.Rect rightCheek
    ) {
    }
}
