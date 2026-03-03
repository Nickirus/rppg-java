package com.example.rppg.vision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RoiSelectorTest {

    @Test
    void multiRegionRois_areInsideFaceAndNonEmpty() {
        FaceTracker.Rect face = new FaceTracker.Rect(100, 80, 220, 260);

        RoiSelector.MultiRoi rois = RoiSelector.multiRegionRois(face);

        assertInsideFace(face, rois.forehead());
        assertInsideFace(face, rois.leftCheek());
        assertInsideFace(face, rois.rightCheek());
    }

    @Test
    void foreheadRoi_isInsideFaceAndNonEmpty() {
        FaceTracker.Rect face = new FaceTracker.Rect(40, 30, 160, 180);

        FaceTracker.Rect roi = RoiSelector.foreheadRoi(face);

        assertInsideFace(face, roi);
    }

    private static void assertInsideFace(FaceTracker.Rect face, FaceTracker.Rect roi) {
        assertTrue(roi.width() > 0, "ROI width must be > 0");
        assertTrue(roi.height() > 0, "ROI height must be > 0");
        assertTrue(roi.x() >= face.x(), "ROI x must be inside face");
        assertTrue(roi.y() >= face.y(), "ROI y must be inside face");
        assertTrue(roi.x() + roi.width() <= face.x() + face.width(), "ROI right bound must be inside face");
        assertTrue(roi.y() + roi.height() <= face.y() + face.height(), "ROI bottom bound must be inside face");
    }
}
