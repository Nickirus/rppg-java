package com.example.rppg.app;

import com.example.rppg.vision.FaceTracker;
import com.example.rppg.vision.RoiSelector;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.CanvasFrame;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;

import javax.swing.JFrame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.LINE_8;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.equalizeHist;
import static org.bytedeco.opencv.global.opencv_imgproc.rectangle;

@Slf4j
public final class CameraSmokeCheck {
    private static final int CAMERA_INDEX = 0;
    private static final long CHECK_DURATION_MS = 5_000L;
    private static final int DETECT_EVERY_N_FRAMES = 3;
    private static final Path CASCADE_PATH =
            Paths.get("src", "main", "resources", "cascades", "haarcascade_frontalface_default.xml");

    private CameraSmokeCheck() {
    }

    public static boolean runDefaultCameraCheck() {
        log.info("Camera smoke-check mode selected.");
        if (!Files.exists(CASCADE_PATH)) {
            log.warn("Camera check failed: missing Haar cascade file.");
            log.warn("Expected file: {}", CASCADE_PATH.toAbsolutePath());
            log.warn("Add the file and rerun. See README section \"Haar Cascade File\".");
            return false;
        }

        CascadeClassifier classifier = new CascadeClassifier(CASCADE_PATH.toString());
        if (classifier.empty()) {
            log.warn("Camera check failed: cannot load Haar cascade file.");
            log.warn("Path: {}", CASCADE_PATH.toAbsolutePath());
            log.warn("Verify the file is a valid OpenCV haarcascade XML.");
            return false;
        }

        OpenCVFrameGrabber grabber = new OpenCVFrameGrabber(CAMERA_INDEX);
        try {
            grabber.start();
        } catch (Exception e) {
            log.warn("Camera check failed: default camera is unavailable or cannot be opened.");
            log.warn("Details: {}", e.getMessage());
            return false;
        }

        CanvasFrame window = null;
        OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();
        try {
            window = new CanvasFrame("rppg-java camera smoke-check");
            window.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

            long startedAtNs = System.nanoTime();
            long startedAtMs = System.currentTimeMillis();
            int framesShown = 0;
            int width = -1;
            int height = -1;
            Rect lastFace = null;

            while (System.currentTimeMillis() - startedAtMs < CHECK_DURATION_MS && window.isVisible()) {
                Frame frame = grabber.grab();
                if (frame == null || frame.image == null) {
                    continue;
                }
                Mat bgr = converter.convert(frame);
                if (bgr == null || bgr.empty()) {
                    continue;
                }

                if (width < 0 || height < 0) {
                    width = bgr.cols();
                    height = bgr.rows();
                }

                if (framesShown % DETECT_EVERY_N_FRAMES == 0) {
                    lastFace = detectLargestFace(classifier, bgr);
                }

                if (lastFace != null) {
                    drawFaceAndForeheadOverlay(bgr, lastFace);
                }

                window.showImage(frame);
                framesShown++;
            }

            if (framesShown == 0) {
                log.warn("Camera check failed: camera opened but no frames were received.");
                return false;
            }

            double elapsedSeconds = (System.nanoTime() - startedAtNs) / 1_000_000_000.0;
            double fps = framesShown / elapsedSeconds;
            String summary = String.format(
                    Locale.US,
                    "Camera check OK: %.2f FPS, %dx%d, frames=%d, duration=%.2fs",
                    fps,
                    width,
                    height,
                    framesShown,
                    elapsedSeconds
            );
            log.info(summary);
            return true;
        } catch (java.awt.HeadlessException e) {
            log.warn("Camera check failed: no display is available for preview window.");
            log.warn("Details: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Camera check failed during preview.");
            log.warn("Details: {}", e.getMessage());
            return false;
        } finally {
            if (window != null) {
                window.dispose();
            }
            classifier.close();
            try {
                grabber.stop();
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            try {
                grabber.release();
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    private static Rect detectLargestFace(CascadeClassifier classifier, Mat bgrFrame) {
        Mat gray = new Mat();
        cvtColor(bgrFrame, gray, COLOR_BGR2GRAY);
        equalizeHist(gray, gray);

        RectVector faces = new RectVector();
        classifier.detectMultiScale(gray, faces, 1.1, 3, 0, new Size(80, 80), new Size());
        if (faces.size() == 0) {
            return null;
        }

        Rect best = null;
        long bestArea = -1L;
        for (int i = 0; i < faces.size(); i++) {
            Rect current = faces.get(i);
            long area = (long) current.width() * current.height();
            if (area > bestArea) {
                bestArea = area;
                best = new Rect(current.x(), current.y(), current.width(), current.height());
            }
        }
        return best;
    }

    private static void drawFaceAndForeheadOverlay(Mat bgrFrame, Rect face) {
        rectangle(bgrFrame, face, new Scalar(0, 255, 0, 0), 2, LINE_8, 0);

        FaceTracker.Rect roi = RoiSelector.foreheadRoi(
                new FaceTracker.Rect(face.x(), face.y(), face.width(), face.height())
        );
        Rect forehead = clampRect(
                new Rect(roi.x(), roi.y(), roi.width(), roi.height()),
                bgrFrame.cols(),
                bgrFrame.rows()
        );
        if (forehead.width() > 0 && forehead.height() > 0) {
            rectangle(bgrFrame, forehead, new Scalar(255, 255, 0, 0), 2, LINE_8, 0);
        }
    }

    private static Rect clampRect(Rect rect, int frameWidth, int frameHeight) {
        int x = Math.max(0, rect.x());
        int y = Math.max(0, rect.y());
        int maxW = Math.max(0, frameWidth - x);
        int maxH = Math.max(0, frameHeight - y);
        int w = Math.max(0, Math.min(rect.width(), maxW));
        int h = Math.max(0, Math.min(rect.height(), maxH));
        return new Rect(x, y, w, h);
    }
}
