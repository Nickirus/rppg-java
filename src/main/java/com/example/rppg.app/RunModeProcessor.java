package com.example.rppg.app;

import com.example.rppg.vision.FaceTracker;
import com.example.rppg.vision.RoiSelector;
import com.example.signal.HeartRateEstimator;
import com.example.signal.SignalWindow;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.bytedeco.opencv.global.opencv_core.mean;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.LINE_8;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.equalizeHist;
import static org.bytedeco.opencv.global.opencv_imgproc.rectangle;

final class RunModeProcessor {
    private static final int DETECT_EVERY_N_FRAMES = 3;
    private static final int MIN_FRAMES_FOR_FPS_ESTIMATE = 15;
    private static final long BPM_UPDATE_INTERVAL_NS = 2_000_000_000L;
    private static final long POST_FULL_MONITOR_NS = 6_000_000_000L;
    private static final long MAX_RUN_DURATION_NS = 120_000_000_000L;
    private static final Path CASCADE_PATH =
            Paths.get("src", "main", "resources", "cascades", "haarcascade_frontalface_default.xml");

    private RunModeProcessor() {
    }

    static boolean run(Config config) {
        if (!Files.exists(CASCADE_PATH)) {
            System.err.println("Run mode failed: missing Haar cascade file.");
            System.err.println("Expected file: " + CASCADE_PATH.toAbsolutePath());
            System.err.println("Add the file and rerun. See README section \"Haar Cascade File\".");
            return false;
        }

        CascadeClassifier classifier = new CascadeClassifier(CASCADE_PATH.toString());
        if (classifier.empty()) {
            System.err.println("Run mode failed: cannot load Haar cascade file.");
            System.err.println("Path: " + CASCADE_PATH.toAbsolutePath());
            System.err.println("Verify the file is a valid OpenCV haarcascade XML.");
            return false;
        }

        OpenCVFrameGrabber grabber = new OpenCVFrameGrabber(config.cameraIndex());
        grabber.setImageWidth(config.targetWidth());
        grabber.setImageHeight(config.targetHeight());
        grabber.setFrameRate(config.targetFps());

        try {
            grabber.start();
        } catch (Exception e) {
            classifier.close();
            System.err.println("Run mode failed: default camera is unavailable or cannot be opened.");
            System.err.println("Details: " + e.getMessage());
            return false;
        }

        CanvasFrame previewWindow = null;
        OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();
        try {
            previewWindow = new CanvasFrame("rppg-java run mode");
            previewWindow.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

            long startedNs = System.nanoTime();
            int capturedFrames = 0;
            Rect lastFace = null;
            SignalWindow signalWindow = null;
            int signalWindowCapacity = -1;
            HeartRateEstimator estimator = null;
            List<Double> warmupSamples = new ArrayList<>();
            long lastBpmUpdateNs = Long.MIN_VALUE;
            long firstFullWindowNs = Long.MIN_VALUE;

            while (previewWindow.isVisible()) {
                long loopNowNs = System.nanoTime();
                if (loopNowNs - startedNs >= MAX_RUN_DURATION_NS) {
                    System.err.println("Run mode timed out before completion.");
                    return false;
                }

                Frame frame = grabber.grab();
                if (frame == null || frame.image == null) {
                    continue;
                }

                Mat bgr = converter.convert(frame);
                if (bgr == null || bgr.empty()) {
                    continue;
                }

                capturedFrames++;
                double elapsedSeconds = (System.nanoTime() - startedNs) / 1_000_000_000.0;
                double measuredFps = elapsedSeconds > 0.0 ? capturedFrames / elapsedSeconds : config.targetFps();

                if (capturedFrames % DETECT_EVERY_N_FRAMES == 0 || lastFace == null) {
                    lastFace = detectLargestFace(classifier, bgr);
                }

                if (lastFace != null) {
                    FaceTracker.Rect forehead = foreheadRoiForFace(lastFace, bgr.cols(), bgr.rows());
                    Rect foreheadRect = new Rect(forehead.x(), forehead.y(), forehead.width(), forehead.height());

                    rectangle(bgr, lastFace, new Scalar(0, 255, 0, 0), 2, LINE_8, 0);
                    if (foreheadRect.width() > 0 && foreheadRect.height() > 0) {
                        rectangle(bgr, foreheadRect, new Scalar(255, 255, 0, 0), 2, LINE_8, 0);
                    }

                    if (foreheadRect.width() > 0 && foreheadRect.height() > 0) {
                        double avgG = averageGreenChannel(bgr, foreheadRect);

                        if (signalWindow == null && capturedFrames >= MIN_FRAMES_FOR_FPS_ESTIMATE) {
                            signalWindowCapacity = Math.max(10, (int) Math.round(config.windowSeconds() * measuredFps));
                            signalWindow = new SignalWindow(signalWindowCapacity);
                            estimator = new HeartRateEstimator(measuredFps, config.hrMinHz(), config.hrMaxHz());
                            for (double sample : warmupSamples) {
                                signalWindow.add(sample);
                            }
                            warmupSamples.clear();
                            System.out.printf(
                                    Locale.US,
                                    "Signal window initialized: %d samples (window=%ds, fps=%.2f)%n",
                                    signalWindowCapacity,
                                    config.windowSeconds(),
                                    measuredFps
                            );
                        }

                        if (signalWindow == null) {
                            warmupSamples.add(avgG);
                            System.out.printf(
                                    Locale.US,
                                    "avgG=%.2f, windowFill=warmup (%d samples)%n",
                                    avgG,
                                    warmupSamples.size()
                            );
                        } else {
                            signalWindow.add(avgG);
                            double fillPercent = (signalWindow.size() * 100.0) / signalWindowCapacity;
                            System.out.printf(
                                    Locale.US,
                                    "avgG=%.2f, windowFill=%.1f%%%n",
                                    avgG,
                                    fillPercent
                            );

                            if (signalWindow.isFull()) {
                                long estimateNowNs = System.nanoTime();
                                if (firstFullWindowNs == Long.MIN_VALUE) {
                                    firstFullWindowNs = estimateNowNs;
                                }
                                if (lastBpmUpdateNs == Long.MIN_VALUE
                                        || estimateNowNs - lastBpmUpdateNs >= BPM_UPDATE_INTERVAL_NS) {
                                    HeartRateEstimator.Result result = estimator.estimate(signalWindow.toArray());
                                    if (result.valid()) {
                                        System.out.printf(
                                                Locale.US,
                                                "BPM update: %.1f bpm (%.3f Hz)%n",
                                                result.bpm(),
                                                result.hz()
                                        );
                                    } else {
                                        System.out.println("BPM update: invalid: " + result.reason());
                                    }
                                    lastBpmUpdateNs = estimateNowNs;
                                }
                                if (estimateNowNs - firstFullWindowNs >= POST_FULL_MONITOR_NS) {
                                    System.out.println("Run mode completed after post-full BPM updates.");
                                    return true;
                                }
                            }
                        }
                    }
                }

                previewWindow.showImage(frame);
            }

            System.err.println("Run mode stopped before signal window was full.");
            return false;
        } catch (java.awt.HeadlessException e) {
            System.err.println("Run mode failed: no display is available for preview window.");
            System.err.println("Details: " + e.getMessage());
            return false;
        } catch (Exception e) {
            System.err.println("Run mode failed during capture.");
            System.err.println("Details: " + e.getMessage());
            return false;
        } finally {
            if (previewWindow != null) {
                previewWindow.dispose();
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

    private static FaceTracker.Rect foreheadRoiForFace(Rect face, int frameWidth, int frameHeight) {
        FaceTracker.Rect forehead = RoiSelector.foreheadRoi(
                new FaceTracker.Rect(face.x(), face.y(), face.width(), face.height())
        );
        int x = Math.max(0, forehead.x());
        int y = Math.max(0, forehead.y());
        int maxW = Math.max(0, frameWidth - x);
        int maxH = Math.max(0, frameHeight - y);
        int w = Math.max(0, Math.min(forehead.width(), maxW));
        int h = Math.max(0, Math.min(forehead.height(), maxH));
        return new FaceTracker.Rect(x, y, w, h);
    }

    private static double averageGreenChannel(Mat bgrFrame, Rect roiRect) {
        Mat roi = new Mat(bgrFrame, roiRect);
        Scalar channelMeans = mean(roi);
        return channelMeans.get(1);
    }
}
