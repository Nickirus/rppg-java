package com.example.rppg.vision;

import com.example.signal.RoiStats;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.junit.jupiter.api.Test;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC3;
import static org.bytedeco.opencv.global.opencv_imgproc.FILLED;
import static org.bytedeco.opencv.global.opencv_imgproc.rectangle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinMaskRoiAnalyzerTest {

    @Test
    void analyze_computesCoverageFromSyntheticMask() {
        Scalar skinColor = findColorForCoverage(0.99, 1.0);
        Scalar nonSkinColor = findColorForCoverage(0.0, 0.01);

        Mat roi = new Mat(10, 10, CV_8UC3, nonSkinColor);
        try {
            rectangle(roi, new Rect(0, 0, 5, 10), skinColor, FILLED, 8, 0);

            SkinMaskRoiAnalyzer.Result result = SkinMaskRoiAnalyzer.analyze(roi, true, 0.1, true);
            assertEquals(0.5, result.skinCoverage(), 0.05);
        } finally {
            roi.close();
        }
    }

    @Test
    void analyze_maskedMeanDiffersFromUnmaskedWhenBackgroundDominates() {
        Scalar skinColor = findColorForCoverage(0.99, 1.0);
        Scalar nonSkinColor = findColorForCoverage(0.0, 0.01);

        Mat roi = new Mat(10, 10, CV_8UC3, nonSkinColor);
        try {
            rectangle(roi, new Rect(0, 0, 3, 10), skinColor, FILLED, 8, 0);

            SkinMaskRoiAnalyzer.Result masked = SkinMaskRoiAnalyzer.analyze(roi, true, 0.2, true);
            SkinMaskRoiAnalyzer.Result unmasked = SkinMaskRoiAnalyzer.analyze(roi, false, 0.2, true);
            RoiStats m = masked.roiStats();
            RoiStats u = unmasked.roiStats();

            assertEquals(0.3, masked.skinCoverage(), 0.05);
            assertTrue(
                    Math.abs(m.meanR() - u.meanR()) > 5.0
                            || Math.abs(m.meanG() - u.meanG()) > 5.0
                            || Math.abs(m.meanB() - u.meanB()) > 5.0
            );
        } finally {
            roi.close();
        }
    }

    private static Scalar findColorForCoverage(double minInclusive, double maxInclusive) {
        for (int b = 0; b <= 255; b += 16) {
            for (int g = 0; g <= 255; g += 16) {
                for (int r = 0; r <= 255; r += 16) {
                    Scalar color = new Scalar(b, g, r, 0);
                    Mat one = new Mat(1, 1, CV_8UC3, color);
                    try {
                        double coverage = SkinMaskRoiAnalyzer.analyze(one, true, 0.0, false).skinCoverage();
                        if (coverage >= minInclusive && coverage <= maxInclusive) {
                            return color;
                        }
                    } finally {
                        one.close();
                    }
                }
            }
        }
        throw new IllegalStateException("Failed to find synthetic color for requested skin coverage range");
    }
}
