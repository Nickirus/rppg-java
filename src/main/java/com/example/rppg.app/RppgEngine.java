package com.example.rppg.app;

import com.example.rppg.vision.FaceTracker;
import com.example.rppg.vision.RoiSelector;
import com.example.signal.AutoSignalMethodSelector;
import com.example.signal.BpmStabilizer;
import com.example.signal.BpmStatus;
import com.example.signal.ChromExtractor;
import com.example.signal.GreenExtractor;
import com.example.signal.HeartRateEstimator;
import com.example.signal.PosExtractor;
import com.example.signal.RoiStats;
import com.example.signal.RppgSignalExtractor;
import com.example.signal.SignalQualityScorer;
import com.example.signal.SignalMethod;
import com.example.signal.SignalWindow;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.bytedeco.opencv.global.opencv_core.mean;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imencode;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.FONT_HERSHEY_SIMPLEX;
import static org.bytedeco.opencv.global.opencv_imgproc.LINE_8;
import static org.bytedeco.opencv.global.opencv_imgproc.LINE_AA;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.equalizeHist;
import static org.bytedeco.opencv.global.opencv_imgproc.putText;
import static org.bytedeco.opencv.global.opencv_imgproc.rectangle;

@Slf4j
@RequiredArgsConstructor
public final class RppgEngine {
    private static final int DETECT_EVERY_N_FRAMES = 3;
    private static final int MIN_FRAMES_FOR_FPS_ESTIMATE = 15;
    private static final long BPM_UPDATE_INTERVAL_NS = 2_000_000_000L;
    private static final long WARNING_LOG_INTERVAL_NS = 2_000_000_000L;
    private static final long DEBUG_FRAME_LOG_INTERVAL_NS = 2_000_000_000L;
    private static final long MOTION_WARNING_HOLD_NS = 1_000_000_000L;
    private static final String WARNING_NO_FACE = "NO_FACE";
    private static final String WARNING_LOW_QUALITY = "LOW_QUALITY";
    private static final String WARNING_LOW_LIGHT = "LOW_LIGHT";
    private static final String WARNING_TOO_MUCH_MOTION = "TOO_MUCH_MOTION";
    private static final Path CASCADE_PATH =
            Paths.get("src", "main", "resources", "cascades", "haarcascade_frontalface_default.xml");

    private final Config config;
    private final AtomicReference<RppgSnapshot> latestSnapshot = new AtomicReference<>(RppgSnapshot.initial());
    private final AtomicReference<byte[]> latestJpegFrame = new AtomicReference<>(null);
    private final AtomicReference<String> sessionFilePath = new AtomicReference<>("");
    private final AtomicLong sessionStartNs = new AtomicLong(0L);
    private final AtomicLong sessionRowCount = new AtomicLong(0L);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final Object lifecycleLock = new Object();

    private Thread workerThread;

    public boolean start() {
        synchronized (lifecycleLock) {
            if (workerThread != null && workerThread.isAlive()) {
                log.info("RppgEngine start requested but already running.");
                return true;
            }
            stopRequested.set(false);
            sessionFilePath.set(config.csvPath());
            sessionStartNs.set(System.nanoTime());
            sessionRowCount.set(0L);
            publish(
                    true,
                    0.0,
                    0.0,
                    0.0,
                    BpmStatus.INVALID,
                    initialActiveSignalMethod(config.signalMethod()),
                    0.0,
                    0.0,
                    0.0,
                    List.of()
            );
            workerThread = new Thread(this::runLoop, "rppg-engine");
            workerThread.setDaemon(true);
            workerThread.start();
            log.info("RppgEngine started. csvPath={}", config.csvPath());
            return true;
        }
    }

    public void stop() {
        log.info("RppgEngine stop requested.");
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
        latestJpegFrame.set(null);
        log.info("RppgEngine stopped.");
    }

    public void reset() {
        log.info("RppgEngine reset requested.");
        stop();
        sessionFilePath.set("");
        sessionStartNs.set(0L);
        sessionRowCount.set(0L);
        latestSnapshot.set(new RppgSnapshot(
                Instant.now().toString(),
                false,
                0.0,
                0.0,
                0.0,
                BpmStatus.INVALID,
                initialActiveSignalMethod(config.signalMethod()),
                0.0,
                0.0,
                0.0,
                List.of(),
                "",
                0.0,
                0L
        ));
        latestJpegFrame.set(null);
        log.info("RppgEngine reset complete.");
    }

    public RppgSnapshot getLatestSnapshot() {
        return latestSnapshot.get();
    }

    public byte[] getLatestJpegFrame() {
        byte[] frame = latestJpegFrame.get();
        if (frame == null) {
            return null;
        }
        return frame.clone();
    }

    private void runLoop() {
        if (!Files.exists(CASCADE_PATH)) {
            log.warn("Missing Haar cascade file: {}", CASCADE_PATH);
            publish(
                    false,
                    0.0,
                    0.0,
                    0.0,
                    BpmStatus.INVALID,
                    initialActiveSignalMethod(config.signalMethod()),
                    0.0,
                    0.0,
                    0.0,
                    List.of("ERROR_MISSING_CASCADE")
            );
            return;
        }

        CascadeClassifier classifier = new CascadeClassifier(CASCADE_PATH.toString());
        if (classifier.empty()) {
            log.warn("Invalid Haar cascade file: {}", CASCADE_PATH);
            publish(
                    false,
                    0.0,
                    0.0,
                    0.0,
                    BpmStatus.INVALID,
                    initialActiveSignalMethod(config.signalMethod()),
                    0.0,
                    0.0,
                    0.0,
                    List.of("ERROR_INVALID_CASCADE")
            );
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
            log.warn("Camera unavailable: {}", e.getMessage());
            publish(
                    false,
                    0.0,
                    0.0,
                    0.0,
                    BpmStatus.INVALID,
                    initialActiveSignalMethod(config.signalMethod()),
                    0.0,
                    0.0,
                    0.0,
                    List.of("ERROR_CAMERA_UNAVAILABLE")
            );
            return;
        }

        OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();
        CsvSignalLogger csvLogger = null;
        try {
            csvLogger = CsvSignalLogger.open(config.csvPath());
            sessionFilePath.set(config.csvPath());
            sessionRowCount.set(0L);

            long startedNs = System.nanoTime();
            int capturedFrames = 0;
            Rect lastFace = null;
            Rect lastDetectedFace = null;
            long lastFaceDetectedNs = startedNs;
            long lastMotionDetectedNs = Long.MIN_VALUE;
            SignalWindow signalWindow = null;
            int signalWindowCapacity = -1;
            HeartRateEstimator estimator = null;
            List<Double> warmupSamples = new ArrayList<>();
            long lastBpmUpdateNs = Long.MIN_VALUE;
            long lastJpegEncodeNs = Long.MIN_VALUE;
            long jpegEncodeIntervalNs = computeJpegEncodeIntervalNs(config.previewJpegFps());
            EnumMap<SignalMethod, RppgSignalExtractor> extractors = createExtractors(config.extractorTemporalWindow());
            AutoSignalMethodSelector methodSelector = new AutoSignalMethodSelector(
                    config.autoFallbackMinHoldSeconds(),
                    config.autoLowQualityUpdatesThreshold(),
                    config.autoSwitchCooldownSeconds()
            );
            SignalMethod activeMethod = methodSelector.current(config.signalMethod());
            BpmStabilizer stabilizer = new BpmStabilizer(config.maxStepPerUpdateBpm());
            BpmStabilizer.Decision latestBpmDecision = BpmStabilizer.Decision.invalid();
            double latestQuality = 0.0;
            double latestAvgG = 0.0;
            WarningLogState warningLogState = new WarningLogState();

            log.info("Engine loop started. sessionCsv={}", config.csvPath());

            while (!stopRequested.get()) {
                Frame frame = grabber.grab();
                if (frame == null || frame.image == null) {
                    continue;
                }

                Mat bgr = converter.convert(frame);
                if (bgr == null || bgr.empty()) {
                    continue;
                }

                long nowNs = System.nanoTime();
                capturedFrames++;
                double elapsedSeconds = (nowNs - startedNs) / 1_000_000_000.0;
                double measuredFps = elapsedSeconds > 0.0 ? capturedFrames / elapsedSeconds : config.targetFps();

                if (capturedFrames % DETECT_EVERY_N_FRAMES == 0 || lastFace == null) {
                    Rect detectedFace = detectLargestFace(classifier, bgr);
                    if (detectedFace != null) {
                        lastFace = detectedFace;
                        lastFaceDetectedNs = nowNs;
                        if (isTooMuchMotion(lastDetectedFace, detectedFace, bgr.cols(), bgr.rows())) {
                            lastMotionDetectedNs = nowNs;
                        }
                        lastDetectedFace = detectedFace;
                    } else {
                        lastFace = null;
                    }
                }

                double fillPercent = computeFillPercent(signalWindow, signalWindowCapacity);
                if (lastFace == null) {
                    List<String> warnings = buildWarnings(false, latestQuality, null, nowNs, lastFaceDetectedNs, lastMotionDetectedNs);
                    logWarningsAndDebug(
                            warnings,
                            nowNs,
                            latestAvgG,
                            measuredFps,
                            fillPercent,
                            latestBpmDecision.bpm(),
                            latestQuality,
                            latestBpmDecision.status(),
                            warningLogState
                    );
                    publish(
                            true,
                            latestAvgG,
                            latestBpmDecision.bpm(),
                            latestBpmDecision.rawBpm(),
                            latestBpmDecision.status(),
                            activeMethod,
                            latestQuality,
                            measuredFps,
                            fillPercent,
                            warnings
                    );
                    if (shouldEncodeJpeg(jpegEncodeIntervalNs, lastJpegEncodeNs)) {
                        renderAndStoreJpegFrame(
                                bgr,
                                null,
                                null,
                                latestAvgG,
                                latestBpmDecision.bpm(),
                                latestBpmDecision.rawBpm(),
                                latestBpmDecision.status(),
                                activeMethod,
                                latestQuality,
                                measuredFps,
                                fillPercent,
                                warnings
                        );
                        lastJpegEncodeNs = nowNs;
                    }
                    continue;
                }

                FaceTracker.Rect forehead = foreheadRoiForFace(lastFace, bgr.cols(), bgr.rows());
                Rect foreheadRect = new Rect(forehead.x(), forehead.y(), forehead.width(), forehead.height());
                if (foreheadRect.width() <= 0 || foreheadRect.height() <= 0) {
                    List<String> warnings = buildWarnings(false, latestQuality, null, nowNs, lastFaceDetectedNs, lastMotionDetectedNs);
                    logWarningsAndDebug(
                            warnings,
                            nowNs,
                            latestAvgG,
                            measuredFps,
                            fillPercent,
                            latestBpmDecision.bpm(),
                            latestQuality,
                            latestBpmDecision.status(),
                            warningLogState
                    );
                    publish(
                            true,
                            latestAvgG,
                            latestBpmDecision.bpm(),
                            latestBpmDecision.rawBpm(),
                            latestBpmDecision.status(),
                            activeMethod,
                            latestQuality,
                            measuredFps,
                            fillPercent,
                            warnings
                    );
                    if (shouldEncodeJpeg(jpegEncodeIntervalNs, lastJpegEncodeNs)) {
                        renderAndStoreJpegFrame(
                                bgr,
                                lastFace,
                                null,
                                latestAvgG,
                                latestBpmDecision.bpm(),
                                latestBpmDecision.rawBpm(),
                                latestBpmDecision.status(),
                                activeMethod,
                                latestQuality,
                                measuredFps,
                                fillPercent,
                                warnings
                        );
                        lastJpegEncodeNs = nowNs;
                    }
                    continue;
                }

                RoiStats roiStats = extractRoiStats(bgr, foreheadRect);
                double avgG = roiStats.meanG();
                double brightness = roiStats.meanBrightness();
                double extractedSample = sanitizeSignalSample(
                        extractorForMethod(extractors, activeMethod).extract(roiStats)
                );
                latestAvgG = avgG;

                if (signalWindow == null && capturedFrames >= MIN_FRAMES_FOR_FPS_ESTIMATE) {
                    signalWindowCapacity = Math.max(10, (int) Math.round(config.windowSeconds() * measuredFps));
                    signalWindow = new SignalWindow(signalWindowCapacity);
                    estimator = new HeartRateEstimator(measuredFps, config.hrMinHz(), config.hrMaxHz());
                    for (double sample : warmupSamples) {
                        signalWindow.add(sample);
                    }
                    warmupSamples.clear();
                    log.info(
                            "Signal window initialized: samples={}, windowSeconds={}, fps={}",
                            signalWindowCapacity,
                            config.windowSeconds(),
                            String.format(Locale.US, "%.2f", measuredFps)
                    );
                }

                if (signalWindow == null) {
                    latestBpmDecision = BpmStabilizer.Decision.invalid();
                    warmupSamples.add(extractedSample);
                    logCsv(csvLogger, Instant.now(), avgG, null, 0.0);
                    List<String> warnings = buildWarnings(false, 0.0, brightness, nowNs, lastFaceDetectedNs, lastMotionDetectedNs);
                    logWarningsAndDebug(
                            warnings,
                            nowNs,
                            avgG,
                            measuredFps,
                            0.0,
                            0.0,
                            0.0,
                            BpmStatus.INVALID,
                            warningLogState
                    );
                    publish(
                            true,
                            avgG,
                            0.0,
                            0.0,
                            BpmStatus.INVALID,
                            activeMethod,
                            0.0,
                            measuredFps,
                            0.0,
                            warnings
                    );
                    if (shouldEncodeJpeg(jpegEncodeIntervalNs, lastJpegEncodeNs)) {
                        renderAndStoreJpegFrame(
                                bgr,
                                lastFace,
                                foreheadRect,
                                avgG,
                                0.0,
                                0.0,
                                BpmStatus.INVALID,
                                activeMethod,
                                0.0,
                                measuredFps,
                                0.0,
                                warnings
                        );
                        lastJpegEncodeNs = nowNs;
                    }
                    continue;
                }

                signalWindow.add(extractedSample);
                fillPercent = computeFillPercent(signalWindow, signalWindowCapacity);

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
                        latestBpmDecision = stabilizer.update(result, quality, config.qualityThreshold());
                        if (latestBpmDecision.status() == BpmStatus.VALID) {
                            log.debug(
                                    "BPM update: bpm={}, rawBpm={}, quality={}, status={}",
                                    String.format(Locale.US, "%.1f", latestBpmDecision.bpm()),
                                    String.format(Locale.US, "%.1f", latestBpmDecision.rawBpm()),
                                    String.format(Locale.US, "%.3f", quality),
                                    latestBpmDecision.status()
                            );
                        } else if (latestBpmDecision.status() == BpmStatus.HOLDING) {
                            log.debug(
                                    "BPM holding: bpm={}, rawBpm={}, quality={} threshold={}",
                                    String.format(Locale.US, "%.1f", latestBpmDecision.bpm()),
                                    formatOptional(latestBpmDecision.rawBpm()),
                                    String.format(Locale.US, "%.3f", quality),
                                    String.format(Locale.US, "%.3f", config.qualityThreshold())
                            );
                        } else {
                            log.debug(
                                    "BPM update invalid: quality={}, reason={}",
                                    String.format(Locale.US, "%.3f", quality),
                                    result.reason()
                            );
                        }
                        SignalMethod nextMethod = methodSelector.onBpmUpdate(
                                config.signalMethod(),
                                latestBpmDecision.status(),
                                latestQuality,
                                config.qualityThreshold(),
                                estimateNowNs
                        );
                        if (nextMethod != activeMethod) {
                            log.info("AUTO signal fallback: {} -> {}", activeMethod, nextMethod);
                            activeMethod = nextMethod;
                            resetExtractors(extractors);
                            signalWindow = new SignalWindow(signalWindowCapacity);
                            warmupSamples.clear();
                            stabilizer.reset();
                            latestBpmDecision = BpmStabilizer.Decision.invalid();
                            latestQuality = 0.0;
                        }
                        lastBpmUpdateNs = estimateNowNs;
                    }
                    logCsv(csvLogger, Instant.now(), avgG, bpmForCsv(latestBpmDecision), latestQuality);
                } else {
                    stabilizer.reset();
                    latestBpmDecision = BpmStabilizer.Decision.invalid();
                    latestQuality = 0.0;
                    logCsv(csvLogger, Instant.now(), avgG, null, 0.0);
                }

                List<String> warnings = buildWarnings(
                        signalWindow.isFull(),
                        latestQuality,
                        brightness,
                        nowNs,
                        lastFaceDetectedNs,
                        lastMotionDetectedNs
                );
                logWarningsAndDebug(
                        warnings,
                        nowNs,
                        avgG,
                        measuredFps,
                        fillPercent,
                        latestBpmDecision.bpm(),
                        latestQuality,
                        latestBpmDecision.status(),
                        warningLogState
                );
                publish(
                        true,
                        avgG,
                        latestBpmDecision.bpm(),
                        latestBpmDecision.rawBpm(),
                        latestBpmDecision.status(),
                        activeMethod,
                        latestQuality,
                        measuredFps,
                        fillPercent,
                        warnings
                );
                if (shouldEncodeJpeg(jpegEncodeIntervalNs, lastJpegEncodeNs)) {
                    renderAndStoreJpegFrame(
                            bgr,
                            lastFace,
                            foreheadRect,
                            avgG,
                            latestBpmDecision.bpm(),
                            latestBpmDecision.rawBpm(),
                            latestBpmDecision.status(),
                            activeMethod,
                            latestQuality,
                            measuredFps,
                            fillPercent,
                            warnings
                    );
                    lastJpegEncodeNs = nowNs;
                }
            }

            publish(
                    false,
                    latestAvgG,
                    latestBpmDecision.bpm(),
                    latestBpmDecision.rawBpm(),
                    latestBpmDecision.status(),
                    activeMethod,
                    latestQuality,
                    0.0,
                    0.0,
                    List.of()
            );
        } catch (Exception e) {
            publish(
                    false,
                    0.0,
                    0.0,
                    0.0,
                    BpmStatus.INVALID,
                    initialActiveSignalMethod(config.signalMethod()),
                    0.0,
                    0.0,
                    0.0,
                    List.of("ERROR_PROCESSING_FAILED")
            );
            log.warn("RppgEngine processing failed: {}", e.getMessage());
        } finally {
            if (csvLogger != null) {
                try {
                    csvLogger.close();
                    log.info("Session CSV closed: {}", config.csvPath());
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
            latestJpegFrame.set(null);
        }
    }

    private List<String> buildWarnings(
            boolean signalReady,
            double quality,
            Double brightness,
            long nowNs,
            long lastFaceDetectedNs,
            long lastMotionDetectedNs
    ) {
        List<String> warnings = new ArrayList<>(4);

        double noFaceElapsedSeconds = (nowNs - lastFaceDetectedNs) / 1_000_000_000.0;
        if (noFaceElapsedSeconds >= config.noFaceWarningSeconds()) {
            warnings.add(WARNING_NO_FACE);
        }
        if (signalReady && quality < config.qualityThreshold()) {
            warnings.add(WARNING_LOW_QUALITY);
        }
        if (brightness != null && brightness < config.lowLightBrightnessThreshold()) {
            warnings.add(WARNING_LOW_LIGHT);
        }
        if (lastMotionDetectedNs != Long.MIN_VALUE && nowNs - lastMotionDetectedNs <= MOTION_WARNING_HOLD_NS) {
            warnings.add(WARNING_TOO_MUCH_MOTION);
        }

        return warnings;
    }

    private void publish(
            boolean running,
            double avgG,
            double bpm,
            double rawBpm,
            BpmStatus bpmStatus,
            SignalMethod signalMethod,
            double quality,
            double fps,
            double windowFill,
            List<String> warnings
    ) {
        long startedNs = sessionStartNs.get();
        double sessionDurationSec = startedNs <= 0L
                ? 0.0
                : (System.nanoTime() - startedNs) / 1_000_000_000.0;
        latestSnapshot.set(new RppgSnapshot(
                Instant.now().toString(),
                running,
                round3(avgG),
                round2(bpm),
                roundOptional(rawBpm),
                bpmStatus == null ? BpmStatus.INVALID : bpmStatus,
                signalMethod == null ? SignalMethod.GREEN : signalMethod,
                round3(quality),
                round2(fps),
                round1(windowFill),
                warnings == null ? List.of() : List.copyOf(warnings),
                sessionFilePath.get(),
                round1(Math.max(0.0, sessionDurationSec)),
                sessionRowCount.get()
        ));
    }

    private void logCsv(CsvSignalLogger logger, Instant timestamp, double avgG, Double bpm, double quality) throws IOException {
        logger.log(timestamp, avgG, bpm, quality);
        sessionRowCount.incrementAndGet();
    }

    private void logWarningsAndDebug(
            List<String> warnings,
            long nowNs,
            double avgG,
            double fps,
            double windowFill,
            double bpm,
            double quality,
            BpmStatus bpmStatus,
            WarningLogState state
    ) {
        boolean noFaceActive = warnings.contains(WARNING_NO_FACE);
        if (shouldEmitThrottledWarning(noFaceActive, nowNs, state.noFaceActive, state.lastNoFaceWarnLogNs)) {
            log.warn("NO_FACE sustained for >= {} seconds.", config.noFaceWarningSeconds());
            state.lastNoFaceWarnLogNs = nowNs;
        }
        state.noFaceActive = noFaceActive;

        boolean lowQualityActive = warnings.contains(WARNING_LOW_QUALITY);
        if (shouldEmitThrottledWarning(lowQualityActive, nowNs, state.lowQualityActive, state.lastLowQualityWarnLogNs)) {
            log.warn(
                    "LOW_QUALITY sustained: quality={} threshold={}",
                    String.format(Locale.US, "%.3f", quality),
                    String.format(Locale.US, "%.3f", config.qualityThreshold())
            );
            state.lastLowQualityWarnLogNs = nowNs;
        }
        state.lowQualityActive = lowQualityActive;

        if (log.isDebugEnabled()
                && (state.lastFrameDebugLogNs == Long.MIN_VALUE || nowNs - state.lastFrameDebugLogNs >= DEBUG_FRAME_LOG_INTERVAL_NS)) {
            log.debug(
                    "Frame update: avgG={}, bpm={}, status={}, quality={}, fps={}, windowFill={}, warnings={}",
                    String.format(Locale.US, "%.2f", avgG),
                    String.format(Locale.US, "%.1f", bpm),
                    bpmStatus,
                    String.format(Locale.US, "%.3f", quality),
                    String.format(Locale.US, "%.1f", fps),
                    String.format(Locale.US, "%.1f", windowFill),
                    warnings
            );
            state.lastFrameDebugLogNs = nowNs;
        }
    }

    private static boolean shouldEmitThrottledWarning(
            boolean currentlyActive,
            long nowNs,
            boolean wasActive,
            long lastLogNs
    ) {
        if (!currentlyActive) {
            return false;
        }
        if (!wasActive) {
            return true;
        }
        return lastLogNs == Long.MIN_VALUE || nowNs - lastLogNs >= WARNING_LOG_INTERVAL_NS;
    }

    private static Double bpmForCsv(BpmStabilizer.Decision decision) {
        if (decision == null || decision.status() == BpmStatus.INVALID) {
            return null;
        }
        return decision.bpm();
    }

    private static SignalMethod initialActiveSignalMethod(SignalMethod configuredMethod) {
        if (configuredMethod == null || configuredMethod == SignalMethod.AUTO) {
            return SignalMethod.POS;
        }
        return configuredMethod;
    }

    private static EnumMap<SignalMethod, RppgSignalExtractor> createExtractors(int temporalWindow) {
        EnumMap<SignalMethod, RppgSignalExtractor> extractors = new EnumMap<>(SignalMethod.class);
        extractors.put(SignalMethod.GREEN, new GreenExtractor());
        extractors.put(SignalMethod.POS, new PosExtractor(temporalWindow));
        extractors.put(SignalMethod.CHROM, new ChromExtractor(temporalWindow));
        return extractors;
    }

    private static RppgSignalExtractor extractorForMethod(
            EnumMap<SignalMethod, RppgSignalExtractor> extractors,
            SignalMethod method
    ) {
        RppgSignalExtractor extractor = extractors.get(method);
        if (extractor != null) {
            return extractor;
        }
        return extractors.get(SignalMethod.GREEN);
    }

    private static void resetExtractors(EnumMap<SignalMethod, RppgSignalExtractor> extractors) {
        for (RppgSignalExtractor extractor : extractors.values()) {
            extractor.reset();
        }
    }

    private static double sanitizeSignalSample(double sample) {
        if (!Double.isFinite(sample)) {
            return 0.0;
        }
        return sample;
    }

    private static double roundOptional(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return round2(value);
    }

    private static String formatOptional(double value) {
        if (!Double.isFinite(value)) {
            return "--";
        }
        return String.format(Locale.US, "%.1f", value);
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

    private static double computeFillPercent(SignalWindow signalWindow, int signalWindowCapacity) {
        if (signalWindow == null || signalWindowCapacity <= 0) {
            return 0.0;
        }
        return (signalWindow.size() * 100.0) / signalWindowCapacity;
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

    private boolean isTooMuchMotion(Rect previousFace, Rect currentFace, int frameWidth, int frameHeight) {
        if (previousFace == null || currentFace == null) {
            return false;
        }

        double prevCx = previousFace.x() + previousFace.width() / 2.0;
        double prevCy = previousFace.y() + previousFace.height() / 2.0;
        double currCx = currentFace.x() + currentFace.width() / 2.0;
        double currCy = currentFace.y() + currentFace.height() / 2.0;

        double dx = currCx - prevCx;
        double dy = currCy - prevCy;
        double frameDiagonal = Math.hypot(Math.max(frameWidth, 1), Math.max(frameHeight, 1));
        double centerDisplacement = Math.hypot(dx, dy) / Math.max(frameDiagonal, 1.0);

        double prevArea = Math.max(1.0, (double) previousFace.width() * previousFace.height());
        double currArea = Math.max(1.0, (double) currentFace.width() * currentFace.height());
        double areaChange = Math.abs(currArea - prevArea) / prevArea;

        return centerDisplacement > config.motionCenterThreshold() || areaChange > config.motionAreaChangeThreshold();
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

    private static RoiStats extractRoiStats(Mat bgrFrame, Rect roiRect) {
        Mat roi = new Mat(bgrFrame, roiRect);
        Scalar channelMeans = mean(roi);
        return new RoiStats(channelMeans.get(2), channelMeans.get(1), channelMeans.get(0));
    }

    private static long computeJpegEncodeIntervalNs(double previewJpegFps) {
        double safeFps = (!Double.isFinite(previewJpegFps) || previewJpegFps <= 0.0) ? 10.0 : previewJpegFps;
        return (long) Math.max(1_000_000L, Math.round(1_000_000_000.0 / safeFps));
    }

    private static boolean shouldEncodeJpeg(long intervalNs, long lastEncodeNs) {
        if (lastEncodeNs == Long.MIN_VALUE) {
            return true;
        }
        return System.nanoTime() - lastEncodeNs >= intervalNs;
    }

    private void renderAndStoreJpegFrame(
            Mat bgrFrame,
            Rect faceRect,
            Rect roiRect,
            double avgG,
            double bpm,
            double rawBpm,
            BpmStatus bpmStatus,
            SignalMethod signalMethod,
            double quality,
            double fps,
            double windowFill,
            List<String> warnings
    ) {
        Mat view = bgrFrame.clone();
        try {
            if (faceRect != null) {
                rectangle(view, faceRect, new Scalar(0, 255, 0, 0), 2, LINE_8, 0);
            }
            if (roiRect != null) {
                rectangle(view, roiRect, new Scalar(255, 255, 0, 0), 2, LINE_8, 0);
            }

            String line1 = String.format(
                    Locale.US,
                    "BPM: %.1f (%s) Raw: %s  Q: %.3f",
                    bpm,
                    bpmStatus,
                    formatOptional(rawBpm),
                    quality
            );
            String line2 = String.format(
                    Locale.US,
                    "FPS: %.1f  Fill: %.1f%%  avgG: %.1f  Method: %s",
                    fps,
                    windowFill,
                    avgG,
                    signalMethod
            );
            String line3 = warnings == null || warnings.isEmpty() ? "Warnings: none" : "Warnings: " + String.join(", ", warnings);
            putText(view, line1, new Point(12, 24), FONT_HERSHEY_SIMPLEX, 0.6, new Scalar(0, 255, 255, 0), 2, LINE_AA, false);
            putText(view, line2, new Point(12, 48), FONT_HERSHEY_SIMPLEX, 0.55, new Scalar(0, 255, 255, 0), 2, LINE_AA, false);
            putText(view, line3, new Point(12, 72), FONT_HERSHEY_SIMPLEX, 0.55, new Scalar(0, 200, 255, 0), 2, LINE_AA, false);

            BytePointer encoded = new BytePointer();
            try {
                if (imencode(".jpg", view, encoded)) {
                    long sizeLong = encoded.limit() > 0 ? encoded.limit() : encoded.capacity();
                    if (sizeLong > 0 && sizeLong <= Integer.MAX_VALUE) {
                        int size = (int) sizeLong;
                        byte[] bytes = new byte[size];
                        encoded.position(0);
                        encoded.get(bytes);
                        latestJpegFrame.set(bytes);
                    }
                }
            } finally {
                encoded.close();
            }
        } finally {
            view.close();
        }
    }

    @Getter
    @ToString
    @EqualsAndHashCode
    private static final class WarningLogState {
        private boolean noFaceActive;
        private boolean lowQualityActive;
        private long lastNoFaceWarnLogNs = Long.MIN_VALUE;
        private long lastLowQualityWarnLogNs = Long.MIN_VALUE;
        private long lastFrameDebugLogNs = Long.MIN_VALUE;
    }
}
