package com.example.rppg.app;

import com.example.rppg.vision.FaceTracker;
import com.example.rppg.vision.RoiSelector;
import com.example.signal.HeartRateEstimator;
import com.example.signal.SignalQualityScorer;
import com.example.signal.SignalWindow;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.bytedeco.opencv.global.opencv_core.mean;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.equalizeHist;

public final class RppgEngine {
    private static final int DETECT_EVERY_N_FRAMES = 3;
    private static final int MIN_FRAMES_FOR_FPS_ESTIMATE = 15;
    private static final long BPM_UPDATE_INTERVAL_NS = 2_000_000_000L;
    private static final Path CASCADE_PATH =
            Paths.get("src", "main", "resources", "cascades", "haarcascade_frontalface_default.xml");

    private final Config config;
    private final AtomicReference<RppgSnapshot> latestSnapshot = new AtomicReference<>(RppgSnapshot.initial());
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final Object lifecycleLock = new Object();

    private Thread workerThread;

    public RppgEngine(Config config) {
        this.config = config;
    }

    public boolean start() {
        synchronized (lifecycleLock) {
            if (workerThread != null && workerThread.isAlive()) {
                return true;
            }
            stopRequested.set(false);
            publish(true, 0.0, 0.0, 0.0, 0.0, 0.0, "starting");
            workerThread = new Thread(this::runLoop, "rppg-engine");
            workerThread.setDaemon(true);
            workerThread.start();
            return true;
        }
    }

    public void stop() {
        Thread threadToJoin;
        synchronized (lifecycleLock) {
            stopRequested.set(true);
            threadToJoin = workerThread;
        }
        if (threadToJoin != null && threadToJoin.isAlive()) {
            try {
                threadToJoin.join(3_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (lifecycleLock) {
            if (workerThread == threadToJoin) {
                workerThread = null;
            }
        }
    }

    public void reset() {
        stop();
        latestSnapshot.set(new RppgSnapshot(
                Instant.now().toString(),
                false,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                "reset"
        ));
    }

    public RppgSnapshot getLatestSnapshot() {
        return latestSnapshot.get();
    }

    private void runLoop() {
        if (!Files.exists(CASCADE_PATH)) {
            publish(false, 0.0, 0.0, 0.0, 0.0, 0.0, "error: missing-cascade");
            return;
        }

        CascadeClassifier classifier = new CascadeClassifier(CASCADE_PATH.toString());
        if (classifier.empty()) {
            publish(false, 0.0, 0.0, 0.0, 0.0, 0.0, "error: invalid-cascade");
            return;
        }

        OpenCVFrameGrabber grabber = new OpenCVFrameGrabber(config.cameraIndex());
        grabber.setImageWidth(config.targetWidth());
        grabber.setImageHeight(config.targetHeight());
        grabber.setFrameRate(config.targetFps());

        try {
            grabber.start();
        } catch (Exception e) {
            classifier.close();
            publish(false, 0.0, 0.0, 0.0, 0.0, 0.0, "error: camera-unavailable");
            return;
        }

        OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();
        CsvSignalLogger csvLogger = null;
        try {
            csvLogger = CsvSignalLogger.open(config.csvPath());

            long startedNs = System.nanoTime();
            int capturedFrames = 0;
            Rect lastFace = null;
            SignalWindow signalWindow = null;
            int signalWindowCapacity = -1;
            HeartRateEstimator estimator = null;
            List<Double> warmupSamples = new ArrayList<>();
            long lastBpmUpdateNs = Long.MIN_VALUE;
            Double latestBpm = null;
            double latestQuality = 0.0;
            String latestWarning = "warming-up";
            double latestAvgG = 0.0;

            while (!stopRequested.get()) {
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

                if (lastFace == null) {
                    publish(true, latestAvgG, valueOrZero(latestBpm), latestQuality, measuredFps, 0.0, "no-face");
                    continue;
                }

                FaceTracker.Rect forehead = foreheadRoiForFace(lastFace, bgr.cols(), bgr.rows());
                Rect foreheadRect = new Rect(forehead.x(), forehead.y(), forehead.width(), forehead.height());
                if (foreheadRect.width() <= 0 || foreheadRect.height() <= 0) {
                    publish(true, latestAvgG, valueOrZero(latestBpm), latestQuality, measuredFps, 0.0, "no-roi");
                    continue;
                }

                double avgG = averageGreenChannel(bgr, foreheadRect);
                latestAvgG = avgG;

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
                    csvLogger.log(Instant.now(), avgG, null, 0.0);
                    publish(true, avgG, 0.0, 0.0, measuredFps, 0.0, "warming-up");
                    continue;
                }

                signalWindow.add(avgG);
                double fillPercent = (signalWindow.size() * 100.0) / signalWindowCapacity;

                if (signalWindow.isFull()) {
                    long estimateNowNs = System.nanoTime();
                    if (lastBpmUpdateNs == Long.MIN_VALUE || estimateNowNs - lastBpmUpdateNs >= BPM_UPDATE_INTERVAL_NS) {
                        double[] windowSignal = signalWindow.toArray();
                        HeartRateEstimator.Result result = estimator.estimate(windowSignal);
                        double quality = SignalQualityScorer.peakDominance(
                                windowSignal,
                                measuredFps,
                                config.hrMinHz(),
                                config.hrMaxHz()
                        );
                        latestQuality = quality;
                        if (result.valid() && quality >= config.qualityThreshold()) {
                            latestBpm = result.bpm();
                            latestWarning = "";
                            System.out.printf(
                                    Locale.US,
                                    "BPM update: %.1f bpm (%.3f Hz), quality=%.3f%n",
                                    result.bpm(),
                                    result.hz(),
                                    quality
                            );
                        } else if (result.valid()) {
                            latestBpm = null;
                            latestWarning = "low-confidence";
                            System.out.printf(
                                    Locale.US,
                                    "BPM update: low-confidence (quality=%.3f < %.3f), marked invalid%n",
                                    quality,
                                    config.qualityThreshold()
                            );
                        } else {
                            latestBpm = null;
                            latestWarning = "invalid-signal";
                            System.out.println("BPM update: invalid: " + result.reason());
                        }
                        lastBpmUpdateNs = estimateNowNs;
                    }
                    csvLogger.log(Instant.now(), avgG, latestBpm, latestQuality);
                } else {
                    latestWarning = "warming-up";
                    latestBpm = null;
                    latestQuality = 0.0;
                    csvLogger.log(Instant.now(), avgG, null, 0.0);
                }

                publish(true, avgG, valueOrZero(latestBpm), latestQuality, measuredFps, fillPercent, latestWarning);
            }

            publish(false, latestAvgG, valueOrZero(latestBpm), latestQuality, 0.0, 0.0, "stopped");
        } catch (Exception e) {
            publish(false, 0.0, 0.0, 0.0, 0.0, 0.0, "error: processing-failed");
            System.err.println("RppgEngine processing failed: " + e.getMessage());
        } finally {
            if (csvLogger != null) {
                try {
                    csvLogger.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
            try {
                grabber.stop();
            } catch (Exception ignored) {
                // ignore
            }
            try {
                grabber.release();
            } catch (Exception ignored) {
                // ignore
            }
            try {
                classifier.close();
            } catch (Exception ignored) {
                // ignore
            }
            synchronized (lifecycleLock) {
                if (Thread.currentThread() == workerThread) {
                    workerThread = null;
                }
            }
        }
    }

    private void publish(boolean running, double avgG, double bpm, double quality, double fps, double windowFill, String warnings) {
        latestSnapshot.set(new RppgSnapshot(
                Instant.now().toString(),
                running,
                round3(avgG),
                round2(bpm),
                round3(quality),
                round2(fps),
                round1(windowFill),
                warnings
        ));
    }

    private static double valueOrZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
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
