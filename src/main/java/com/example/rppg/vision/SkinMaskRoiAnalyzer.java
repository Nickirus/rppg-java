package com.example.rppg.vision;

import com.example.signal.RoiStats;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC3;
import static org.bytedeco.opencv.global.opencv_core.countNonZero;
import static org.bytedeco.opencv.global.opencv_core.inRange;
import static org.bytedeco.opencv.global.opencv_core.mean;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2YCrCb;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;

public final class SkinMaskRoiAnalyzer {
    // Lightweight YCrCb skin range for 8-bit BGR images.
    private static final Scalar SKIN_LOWER = new Scalar(0.0, 133.0, 77.0, 0.0);
    private static final Scalar SKIN_UPPER = new Scalar(255.0, 173.0, 127.0, 0.0);

    private SkinMaskRoiAnalyzer() {
    }

    public static Result analyze(
            Mat bgrRoi,
            boolean skinEnabled,
            double minCoverage,
            boolean fallbackToUnmasked
    ) {
        if (bgrRoi == null || bgrRoi.empty()) {
            return new Result(new RoiStats(0.0, 0.0, 0.0), 0.0);
        }

        RoiStats unmasked = toRgbStats(mean(bgrRoi));
        if (!skinEnabled) {
            return new Result(unmasked, 1.0);
        }

        double safeMinCoverage = sanitizeMinCoverage(minCoverage);
        int totalPixels = Math.max(1, bgrRoi.rows() * bgrRoi.cols());

        Mat yCrCb = new Mat();
        Mat skinMask = new Mat();
        Mat lower = new Mat();
        Mat upper = new Mat();
        try {
            cvtColor(bgrRoi, yCrCb, COLOR_BGR2YCrCb);
            lower = new Mat(yCrCb.rows(), yCrCb.cols(), CV_8UC3, SKIN_LOWER);
            upper = new Mat(yCrCb.rows(), yCrCb.cols(), CV_8UC3, SKIN_UPPER);
            inRange(yCrCb, lower, upper, skinMask);

            int skinPixels = Math.max(0, countNonZero(skinMask));
            double skinCoverage = Math.max(0.0, Math.min(1.0, (double) skinPixels / (double) totalPixels));

            boolean useMasked = skinCoverage >= safeMinCoverage || !fallbackToUnmasked;
            RoiStats chosen;
            if (useMasked && skinPixels > 0) {
                chosen = toRgbStats(mean(bgrRoi, skinMask));
            } else if (useMasked) {
                chosen = new RoiStats(0.0, 0.0, 0.0);
            } else {
                chosen = unmasked;
            }
            return new Result(chosen, skinCoverage);
        } finally {
            if (!lower.isNull()) {
                lower.close();
            }
            if (!upper.isNull()) {
                upper.close();
            }
            yCrCb.close();
            skinMask.close();
        }
    }

    private static double sanitizeMinCoverage(double value) {
        if (!Double.isFinite(value)) {
            return 0.25;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static RoiStats toRgbStats(Scalar bgrMean) {
        return new RoiStats(bgrMean.get(2), bgrMean.get(1), bgrMean.get(0));
    }

    public record Result(RoiStats roiStats, double skinCoverage) {
    }
}
