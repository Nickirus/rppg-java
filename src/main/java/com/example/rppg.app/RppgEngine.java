package com.example.rppg.app;

import com.example.rppg.vision.FaceRectSmoother;
import com.example.rppg.vision.FaceTracker;
import com.example.rppg.vision.RoiMode;
import com.example.rppg.vision.RoiSelector;
import com.example.signal.AutoModeState;
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
    private static final int DETECT_EVERY_N_FRAMES = 1;
    private static final int MIN_FRAMES_FOR_FPS_ESTIMATE = 15;
    private static final long WARNING_LOG_INTERVAL_NS = 2_000_000_000L;
    private static final long DEBUG_FRAME_LOG_INTERVAL_NS = 2_000_000_000L;
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
                    AutoModeState.STABLE,
                    null,
                    0.0,
                    ProcessingStatus.NORMAL,
                    0.0,
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
                AutoModeState.STABLE,
                null,
                0.0,
                ProcessingStatus.NORMAL,
                0.0,
                config.roiMode(),
                snapshotRoiWeights(),
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
                    AutoModeState.STABLE,
                    null,
                    0.0,
                    ProcessingStatus.NORMAL,
                    0.0,
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
                    AutoModeState.STABLE,
                    null,
                    0.0,
                    ProcessingStatus.NORMAL,
                    0.0,
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
                    AutoModeState.STABLE,
                    null,
                    0.0,
                    ProcessingStatus.NORMAL,
                    0.0,
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
            long lastFaceDetectedNs = startedNs;
            FaceRectSmoother faceRectSmoother = new FaceRectSmoother(
                    config.faceSmoothingAlpha(),
                    config.faceSmoothingMaxStep()
            );
            MotionGate motionGate = new MotionGate(
                    config.motionThreshold(),
                    config.motionFreezeMinMs(),
                    config.motionResetAfterMs()
            );
            SignalWindow signalWindow = null;
            int signalWindowCapacity = -1;
            HeartRateEstimator estimator = null;
            List<Double> warmupSamples = new ArrayList<>();
            long lastBpmUpdateNs = Long.MIN_VALUE;
            long bpmUpdateIntervalNs = Math.max(1L, config.windowUpdateIntervalMs()) * 1_000_000L;
            long lastJpegEncodeNs = Long.MIN_VALUE;
            long jpegEncodeIntervalNs = computeJpegEncodeIntervalNs(config.previewJpegFps());
            EnumMap<SignalMethod, RppgSignalExtractor> extractors = createExtractors(config.extractorTemporalWindow());
            SignalQualityScorer.Quality2Config quality2Config = new SignalQualityScorer.Quality2Config(
                    config.quality2SnrLow(),
                    config.quality2SnrHigh(),
                    config.quality2MarginLow(),
                    config.quality2MarginHigh(),
                    config.quality2HarmonicEnabled(),
                    config.quality2HarmonicLow(),
                    config.quality2HarmonicHigh(),
                    config.quality2SnrWeight(),
                    config.quality2MarginWeight(),
                    config.quality2HarmonicWeight()
            );
            AutoSignalMethodSelector methodSelector = new AutoSignalMethodSelector(
                    config.autoFallbackMinHoldSeconds(),
                    config.autoLowQualityUpdatesThreshold(),
                    config.autoSwitchCooldownSeconds(),
                    config.autoRecoveryCooldownSeconds(),
                    config.autoProbeWindowSeconds(),
                    config.autoProbeValidRatioThreshold(),
                    config.autoProbeQualityMargin()
            );
            AutoSignalMethodSelector.Decision autoDecision =
                    methodSelector.current(config.signalMethod(), System.nanoTime());
            BpmStabilizer stabilizer = new BpmStabilizer(config.maxStepPerUpdateBpm());
            BpmStabilizer.Decision latestBpmDecision = BpmStabilizer.Decision.invalid();
            double latestQuality = 0.0;
            double latestAvgG = 0.0;
            double latestPeakHz = Double.NaN;
            double latestMotionScore = 0.0;
            double latestSmoothedRectDelta = 0.0;
            ProcessingStatus latestProcessingStatus = ProcessingStatus.NORMAL;
            long lastCsvLogNs = Long.MIN_VALUE;
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
                autoDecision = methodSelector.current(config.signalMethod(), nowNs);

                if (capturedFrames % DETECT_EVERY_N_FRAMES == 0 || lastFace == null) {
                    Rect detectedFace = detectLargestFace(classifier, bgr);
                    if (detectedFace != null) {
                        lastFace = detectedFace;
                        lastFaceDetectedNs = nowNs;
                    } else {
                        lastFace = null;
                    }
                }

                FaceTracker.Rect rawFaceRect = lastFace == null
                        ? null
                        : new FaceTracker.Rect(lastFace.x(), lastFace.y(), lastFace.width(), lastFace.height());
                MotionGate.State motionState = motionGate.update(rawFaceRect, nowNs);
                latestMotionScore = motionState.motionScore();
                latestProcessingStatus = motionState.frozen() ? ProcessingStatus.MOTION_FREEZE : ProcessingStatus.NORMAL;
                FaceTracker.Rect smoothedFaceRect = null;
                if (rawFaceRect == null) {
                    faceRectSmoother.reset();
                    latestSmoothedRectDelta = 0.0;
                } else {
                    FaceRectSmoother.Result smoothResult = faceRectSmoother.update(rawFaceRect, bgr.cols(), bgr.rows());
                    smoothedFaceRect = smoothResult.smoothedRect();
                    latestSmoothedRectDelta = smoothResult.deltaNormalized();
                }
                if (motionState.shouldResetWindow()) {
                    if (signalWindow != null && signalWindowCapacity > 0) {
                        signalWindow = new SignalWindow(signalWindowCapacity);
                    }
                    warmupSamples.clear();
                    latestQuality = 0.0;
                    latestBpmDecision = stabilizer.holdCurrent();
                    lastBpmUpdateNs = Long.MIN_VALUE;
                    methodSelector.resetTimers(nowNs);
                    autoDecision = methodSelector.current(config.signalMethod(), nowNs);
                    log.info(
                            "Motion freeze reset triggered after {} ms, status={}",
                            config.motionResetAfterMs(),
                            latestBpmDecision.status()
                    );
                }

                double fillPercent = computeFillPercent(signalWindow, signalWindowCapacity);
                if (lastFace == null) {
                    List<String> warnings = buildWarnings(
                            false,
                            latestQuality,
                            null,
                            nowNs,
                            lastFaceDetectedNs,
                            latestProcessingStatus
                    );
                    logWarningsAndDebug(
                            warnings,
                            nowNs,
                            latestAvgG,
                            measuredFps,
                            fillPercent,
                            latestBpmDecision.bpm(),
                            latestQuality,
                            latestBpmDecision.status(),
                            latestProcessingStatus,
                            latestMotionScore,
                            warningLogState
                    );
                    publish(
                            true,
                            latestAvgG,
                            latestBpmDecision.bpm(),
                            latestBpmDecision.rawBpm(),
                            latestBpmDecision.status(),
                            autoDecision.activeMethod(),
                            autoDecision.autoModeState(),
                            autoDecision.probeCandidate(),
                            autoDecision.probeSecondsRemaining(),
                            latestProcessingStatus,
                            latestMotionScore,
                            latestQuality,
                            measuredFps,
                            fillPercent,
                            warnings
                    );
                    lastCsvLogNs = maybeLogCsvOnTick(
                            csvLogger,
                            nowNs,
                            lastCsvLogNs,
                            bpmUpdateIntervalNs,
                            latestAvgG,
                            latestBpmDecision,
                            latestQuality,
                            autoDecision.activeMethod(),
                            autoDecision.autoModeState(),
                            latestMotionScore,
                            latestSmoothedRectDelta,
                            latestProcessingStatus,
                            fillPercent,
                            measuredFps,
                            latestPeakHz
                    );
                    if (shouldEncodeJpeg(jpegEncodeIntervalNs, lastJpegEncodeNs)) {
                        renderAndStoreJpegFrame(
                                bgr,
                                null,
                                null,
                                null,
                                null,
                                latestAvgG,
                                latestBpmDecision.bpm(),
                                latestBpmDecision.rawBpm(),
                                latestBpmDecision.status(),
                                autoDecision.activeMethod(),
                                autoDecision.autoModeState(),
                                autoDecision.probeCandidate(),
                                autoDecision.probeSecondsRemaining(),
                                latestProcessingStatus,
                                latestMotionScore,
                                latestQuality,
                                measuredFps,
                                fillPercent,
                                warnings
                        );
                        lastJpegEncodeNs = nowNs;
                    }
                    continue;
                }

                Rect roiFaceRect = smoothedFaceRect == null
                        ? lastFace
                        : new Rect(
                        smoothedFaceRect.x(),
                        smoothedFaceRect.y(),
                        smoothedFaceRect.width(),
                        smoothedFaceRect.height()
                );
                FrameRois frameRois = roiRectsForFace(roiFaceRect, bgr.cols(), bgr.rows(), config.roiMode());
                if (!frameRois.isUsable(config.roiMode())) {
                    List<String> warnings = buildWarnings(
                            false,
                            latestQuality,
                            null,
                            nowNs,
                            lastFaceDetectedNs,
                            latestProcessingStatus
                    );
                    logWarningsAndDebug(
                            warnings,
                            nowNs,
                            latestAvgG,
                            measuredFps,
                            fillPercent,
                            latestBpmDecision.bpm(),
                            latestQuality,
                            latestBpmDecision.status(),
                            latestProcessingStatus,
                            latestMotionScore,
                            warningLogState
                    );
                    publish(
                            true,
                            latestAvgG,
                            latestBpmDecision.bpm(),
                            latestBpmDecision.rawBpm(),
                            latestBpmDecision.status(),
                            autoDecision.activeMethod(),
                            autoDecision.autoModeState(),
                            autoDecision.probeCandidate(),
                            autoDecision.probeSecondsRemaining(),
                            latestProcessingStatus,
                            latestMotionScore,
                            latestQuality,
                            measuredFps,
                            fillPercent,
                            warnings
                    );
                    lastCsvLogNs = maybeLogCsvOnTick(
                            csvLogger,
                            nowNs,
                            lastCsvLogNs,
                            bpmUpdateIntervalNs,
                            latestAvgG,
                            latestBpmDecision,
                            latestQuality,
                            autoDecision.activeMethod(),
                            autoDecision.autoModeState(),
                            latestMotionScore,
                            latestSmoothedRectDelta,
                            latestProcessingStatus,
                            fillPercent,
                            measuredFps,
                            latestPeakHz
                    );
                    if (shouldEncodeJpeg(jpegEncodeIntervalNs, lastJpegEncodeNs)) {
                        renderAndStoreJpegFrame(
                                bgr,
                                lastFace,
                                frameRois.forehead(),
                                frameRois.leftCheek(),
                                frameRois.rightCheek(),
                                latestAvgG,
                                latestBpmDecision.bpm(),
                                latestBpmDecision.rawBpm(),
                                latestBpmDecision.status(),
                                autoDecision.activeMethod(),
                                autoDecision.autoModeState(),
                                autoDecision.probeCandidate(),
                                autoDecision.probeSecondsRemaining(),
                                latestProcessingStatus,
                                latestMotionScore,
                                latestQuality,
                                measuredFps,
                                fillPercent,
                                warnings
                        );
                        lastJpegEncodeNs = nowNs;
                    }
                    continue;
                }

                RoiStats roiStats = extractCombinedRoiStats(
                        bgr,
                        frameRois,
                        config.roiMode(),
                        config.roiForeheadWeight(),
                        config.roiLeftCheekWeight(),
                        config.roiRightCheekWeight()
                );
                double avgG = roiStats.meanG();
                double brightness = roiStats.meanBrightness();
                double extractedSample = sanitizeSignalSample(
                        extractorForMethod(extractors, autoDecision.effectiveMethod()).extract(roiStats)
                );
                latestAvgG = avgG;

                if (signalWindow == null && capturedFrames >= MIN_FRAMES_FOR_FPS_ESTIMATE) {
                    signalWindowCapacity = Math.max(10, (int) Math.round(config.windowSeconds() * measuredFps));
                    signalWindow = new SignalWindow(signalWindowCapacity);
                    estimator = new HeartRateEstimator(
                            measuredFps,
                            config.hrMinHz(),
                            config.hrMaxHz(),
                            config.temporalNormalizationEnabled(),
                            config.temporalNormalizationEps()
                    );
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
                    latestBpmDecision = motionState.frozen()
                            ? stabilizer.holdCurrent()
                            : BpmStabilizer.Decision.invalid();
                    if (!motionState.frozen()) {
                        warmupSamples.add(extractedSample);
                    }
                    List<String> warnings = buildWarnings(
                            false,
                            0.0,
                            brightness,
                            nowNs,
                            lastFaceDetectedNs,
                            latestProcessingStatus
                    );
                    logWarningsAndDebug(
                            warnings,
                            nowNs,
                            avgG,
                            measuredFps,
                            0.0,
                            0.0,
                            0.0,
                            BpmStatus.INVALID,
                            latestProcessingStatus,
                            latestMotionScore,
                            warningLogState
                    );
                    publish(
                            true,
                            avgG,
                            latestBpmDecision.bpm(),
                            latestBpmDecision.rawBpm(),
                            latestBpmDecision.status(),
                            autoDecision.activeMethod(),
                            autoDecision.autoModeState(),
                            autoDecision.probeCandidate(),
                            autoDecision.probeSecondsRemaining(),
                            latestProcessingStatus,
                            latestMotionScore,
                            0.0,
                            measuredFps,
                            0.0,
                            warnings
                    );
                    lastCsvLogNs = maybeLogCsvOnTick(
                            csvLogger,
                            nowNs,
                            lastCsvLogNs,
                            bpmUpdateIntervalNs,
                            avgG,
                            latestBpmDecision,
                            0.0,
                            autoDecision.activeMethod(),
                            autoDecision.autoModeState(),
                            latestMotionScore,
                            latestSmoothedRectDelta,
                            latestProcessingStatus,
                            0.0,
                            measuredFps,
                            latestPeakHz
                    );
                    if (shouldEncodeJpeg(jpegEncodeIntervalNs, lastJpegEncodeNs)) {
                        renderAndStoreJpegFrame(
                                bgr,
                                lastFace,
                                frameRois.forehead(),
                                frameRois.leftCheek(),
                                frameRois.rightCheek(),
                                avgG,
                                latestBpmDecision.bpm(),
                                latestBpmDecision.rawBpm(),
                                latestBpmDecision.status(),
                                autoDecision.activeMethod(),
                                autoDecision.autoModeState(),
                                autoDecision.probeCandidate(),
                                autoDecision.probeSecondsRemaining(),
                                latestProcessingStatus,
                                latestMotionScore,
                                0.0,
                                measuredFps,
                                0.0,
                                warnings
                        );
                        lastJpegEncodeNs = nowNs;
                    }
                    continue;
                }

                if (motionState.frozen()) {
                    fillPercent = computeFillPercent(signalWindow, signalWindowCapacity);
                    latestBpmDecision = stabilizer.holdCurrent();
                    latestQuality = 0.0;
                    List<String> warnings = buildWarnings(
                            signalWindow.isFull(),
                            latestQuality,
                            brightness,
                            nowNs,
                            lastFaceDetectedNs,
                            latestProcessingStatus
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
                            latestProcessingStatus,
                            latestMotionScore,
                            warningLogState
                    );
                    publish(
                            true,
                            avgG,
                            latestBpmDecision.bpm(),
                            latestBpmDecision.rawBpm(),
                            latestBpmDecision.status(),
                            autoDecision.activeMethod(),
                            autoDecision.autoModeState(),
                            autoDecision.probeCandidate(),
                            autoDecision.probeSecondsRemaining(),
                            latestProcessingStatus,
                            latestMotionScore,
                            latestQuality,
                            measuredFps,
                            fillPercent,
                            warnings
                    );
                    lastCsvLogNs = maybeLogCsvOnTick(
                            csvLogger,
                            nowNs,
                            lastCsvLogNs,
                            bpmUpdateIntervalNs,
                            avgG,
                            latestBpmDecision,
                            latestQuality,
                            autoDecision.activeMethod(),
                            autoDecision.autoModeState(),
                            latestMotionScore,
                            latestSmoothedRectDelta,
                            latestProcessingStatus,
                            fillPercent,
                            measuredFps,
                            latestPeakHz
                    );
                    if (shouldEncodeJpeg(jpegEncodeIntervalNs, lastJpegEncodeNs)) {
                        renderAndStoreJpegFrame(
                                bgr,
                                lastFace,
                                frameRois.forehead(),
                                frameRois.leftCheek(),
                                frameRois.rightCheek(),
                                avgG,
                                latestBpmDecision.bpm(),
                                latestBpmDecision.rawBpm(),
                                latestBpmDecision.status(),
                                autoDecision.activeMethod(),
                                autoDecision.autoModeState(),
                                autoDecision.probeCandidate(),
                                autoDecision.probeSecondsRemaining(),
                                latestProcessingStatus,
                                latestMotionScore,
                                latestQuality,
                                measuredFps,
                                fillPercent,
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
                    if (lastBpmUpdateNs == Long.MIN_VALUE || estimateNowNs - lastBpmUpdateNs >= bpmUpdateIntervalNs) {
                        double[] windowSignal = signalWindow.toArray();
                        HeartRateEstimator.Result result = estimator.estimate(windowSignal);
                        double quality = SignalQualityScorer.quality(
                                windowSignal,
                                measuredFps,
                                config.hrMinHz(),
                                config.hrMaxHz(),
                                config.qualityMode(),
                                config.temporalNormalizationEnabled(),
                                config.temporalNormalizationEps(),
                                quality2Config
                        );
                        double quality2 = SignalQualityScorer.quality2(
                                windowSignal,
                                measuredFps,
                                config.hrMinHz(),
                                config.hrMaxHz(),
                                config.temporalNormalizationEnabled(),
                                config.temporalNormalizationEps(),
                                quality2Config
                        );
                        double autoQuality = config.autoUseQuality2ForGating() ? quality2 : quality;
                        latestQuality = quality;
                        latestPeakHz = result.hz();
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
                        AutoSignalMethodSelector.Decision nextDecision = methodSelector.onBpmUpdate(
                                config.signalMethod(),
                                latestBpmDecision.status(),
                                autoQuality,
                                config.qualityThreshold(),
                                estimateNowNs
                        );
                        if (nextDecision.processingResetRequired()) {
                            log.info(
                                    "AUTO method transition: active={} effective={} state={} candidate={}",
                                    nextDecision.activeMethod(),
                                    nextDecision.effectiveMethod(),
                                    nextDecision.autoModeState(),
                                    nextDecision.probeCandidate()
                            );
                            resetExtractors(extractors);
                            signalWindow = new SignalWindow(signalWindowCapacity);
                            warmupSamples.clear();
                            stabilizer.reset();
                            latestBpmDecision = BpmStabilizer.Decision.invalid();
                            latestQuality = 0.0;
                        }
                        autoDecision = nextDecision;
                        lastBpmUpdateNs = estimateNowNs;
                    }
                } else {
                    latestBpmDecision = stabilizer.holdCurrent();
                    latestQuality = 0.0;
                }

                List<String> warnings = buildWarnings(
                        signalWindow.isFull(),
                        latestQuality,
                        brightness,
                        nowNs,
                        lastFaceDetectedNs,
                        latestProcessingStatus
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
                        latestProcessingStatus,
                        latestMotionScore,
                        warningLogState
                );
                publish(
                        true,
                        avgG,
                        latestBpmDecision.bpm(),
                        latestBpmDecision.rawBpm(),
                        latestBpmDecision.status(),
                        autoDecision.activeMethod(),
                        autoDecision.autoModeState(),
                        autoDecision.probeCandidate(),
                        autoDecision.probeSecondsRemaining(),
                        latestProcessingStatus,
                        latestMotionScore,
                        latestQuality,
                        measuredFps,
                        fillPercent,
                        warnings
                );
                lastCsvLogNs = maybeLogCsvOnTick(
                        csvLogger,
                        nowNs,
                        lastCsvLogNs,
                        bpmUpdateIntervalNs,
                        avgG,
                        latestBpmDecision,
                        latestQuality,
                        autoDecision.activeMethod(),
                        autoDecision.autoModeState(),
                        latestMotionScore,
                        latestSmoothedRectDelta,
                        latestProcessingStatus,
                        fillPercent,
                        measuredFps,
                        latestPeakHz
                );
                if (shouldEncodeJpeg(jpegEncodeIntervalNs, lastJpegEncodeNs)) {
                    renderAndStoreJpegFrame(
                            bgr,
                            lastFace,
                            frameRois.forehead(),
                            frameRois.leftCheek(),
                            frameRois.rightCheek(),
                            avgG,
                            latestBpmDecision.bpm(),
                            latestBpmDecision.rawBpm(),
                            latestBpmDecision.status(),
                            autoDecision.activeMethod(),
                            autoDecision.autoModeState(),
                            autoDecision.probeCandidate(),
                            autoDecision.probeSecondsRemaining(),
                            latestProcessingStatus,
                            latestMotionScore,
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
                    autoDecision.activeMethod(),
                    autoDecision.autoModeState(),
                    autoDecision.probeCandidate(),
                    autoDecision.probeSecondsRemaining(),
                    latestProcessingStatus,
                    latestMotionScore,
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
                    AutoModeState.STABLE,
                    null,
                    0.0,
                    ProcessingStatus.NORMAL,
                    0.0,
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
            ProcessingStatus processingStatus
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
        if (processingStatus == ProcessingStatus.MOTION_FREEZE) {
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
            SignalMethod activeSignalMethod,
            AutoModeState autoModeState,
            SignalMethod probeCandidate,
            double probeSecondsRemaining,
            ProcessingStatus processingStatus,
            double motionScore,
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
                activeSignalMethod == null ? SignalMethod.GREEN : activeSignalMethod,
                autoModeState == null ? AutoModeState.STABLE : autoModeState,
                probeCandidate,
                round1(Math.max(0.0, probeSecondsRemaining)),
                processingStatus == null ? ProcessingStatus.NORMAL : processingStatus,
                round3(Math.max(0.0, motionScore)),
                config.roiMode(),
                snapshotRoiWeights(),
                round3(quality),
                round2(fps),
                round1(windowFill),
                warnings == null ? List.of() : List.copyOf(warnings),
                sessionFilePath.get(),
                round1(Math.max(0.0, sessionDurationSec)),
                sessionRowCount.get()
        ));
    }

    private long maybeLogCsvOnTick(
            CsvSignalLogger logger,
            long nowNs,
            long lastCsvLogNs,
            long logIntervalNs,
            double avgG,
            BpmStabilizer.Decision bpmDecision,
            double quality,
            SignalMethod activeSignalMethod,
            AutoModeState autoModeState,
            double motionScore,
            double smoothedRectDelta,
            ProcessingStatus processingStatus,
            double windowFill,
            double fps,
            double peakHz
    ) throws IOException {
        if (logger == null) {
            return lastCsvLogNs;
        }
        if (lastCsvLogNs != Long.MIN_VALUE && nowNs - lastCsvLogNs < logIntervalNs) {
            return lastCsvLogNs;
        }
        logger.log(
                Instant.now(),
                avgG,
                bpmForCsv(bpmDecision),
                quality,
                bpmDecision == null ? Double.NaN : bpmDecision.rawBpm(),
                bpmDecision == null ? BpmStatus.INVALID : bpmDecision.status(),
                activeSignalMethod,
                autoModeState,
                motionScore,
                smoothedRectDelta,
                processingStatus,
                windowFill,
                fps,
                peakHz
        );
        sessionRowCount.incrementAndGet();
        return nowNs;
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
            ProcessingStatus processingStatus,
            double motionScore,
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

        boolean tooMuchMotionActive = warnings.contains(WARNING_TOO_MUCH_MOTION);
        if (shouldEmitThrottledWarning(tooMuchMotionActive, nowNs, state.tooMuchMotionActive, state.lastMotionWarnLogNs)) {
            log.warn(
                    "TOO_MUCH_MOTION sustained: status={} motionScore={} threshold={}",
                    processingStatus,
                    String.format(Locale.US, "%.3f", motionScore),
                    String.format(Locale.US, "%.3f", config.motionThreshold())
            );
            state.lastMotionWarnLogNs = nowNs;
        }
        state.tooMuchMotionActive = tooMuchMotionActive;

        if (log.isDebugEnabled()
                && (state.lastFrameDebugLogNs == Long.MIN_VALUE || nowNs - state.lastFrameDebugLogNs >= DEBUG_FRAME_LOG_INTERVAL_NS)) {
            log.debug(
                    "Frame update: avgG={}, bpm={}, status={}, processing={}, motionScore={}, quality={}, fps={}, windowFill={}, warnings={}",
                    String.format(Locale.US, "%.2f", avgG),
                    String.format(Locale.US, "%.1f", bpm),
                    bpmStatus,
                    processingStatus,
                    String.format(Locale.US, "%.3f", motionScore),
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

    private List<Double> snapshotRoiWeights() {
        if (config.roiMode() == RoiMode.FOREHEAD_ONLY) {
            return List.of(1.0, 0.0, 0.0);
        }
        double[] weights = normalizedRoiWeights(
                config.roiForeheadWeight(),
                config.roiLeftCheekWeight(),
                config.roiRightCheekWeight()
        );
        return List.of(round3(weights[0]), round3(weights[1]), round3(weights[2]));
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

    private static FrameRois roiRectsForFace(Rect face, int frameWidth, int frameHeight, RoiMode roiMode) {
        FaceTracker.Rect faceRect = new FaceTracker.Rect(face.x(), face.y(), face.width(), face.height());
        if (roiMode == RoiMode.FOREHEAD_ONLY) {
            Rect forehead = toOpenCvRect(clampToFrame(RoiSelector.foreheadRoi(faceRect), frameWidth, frameHeight));
            return new FrameRois(forehead, null, null);
        }
        RoiSelector.MultiRoi multiRoi = RoiSelector.multiRegionRois(faceRect);
        Rect forehead = toOpenCvRect(clampToFrame(multiRoi.forehead(), frameWidth, frameHeight));
        Rect leftCheek = toOpenCvRect(clampToFrame(multiRoi.leftCheek(), frameWidth, frameHeight));
        Rect rightCheek = toOpenCvRect(clampToFrame(multiRoi.rightCheek(), frameWidth, frameHeight));
        return new FrameRois(forehead, leftCheek, rightCheek);
    }

    private static FaceTracker.Rect clampToFrame(FaceTracker.Rect roi, int frameWidth, int frameHeight) {
        int x = Math.max(0, roi.x());
        int y = Math.max(0, roi.y());
        int maxW = Math.max(0, frameWidth - x);
        int maxH = Math.max(0, frameHeight - y);
        int w = Math.max(0, Math.min(roi.width(), maxW));
        int h = Math.max(0, Math.min(roi.height(), maxH));
        return new FaceTracker.Rect(x, y, w, h);
    }

    private static Rect toOpenCvRect(FaceTracker.Rect rect) {
        return new Rect(rect.x(), rect.y(), rect.width(), rect.height());
    }

    private static boolean isValidRect(Rect rect) {
        return rect != null && rect.width() > 0 && rect.height() > 0;
    }

    private static RoiStats extractSingleRoiStats(Mat bgrFrame, Rect roiRect) {
        Mat roi = new Mat(bgrFrame, roiRect);
        Scalar channelMeans = mean(roi);
        return new RoiStats(channelMeans.get(2), channelMeans.get(1), channelMeans.get(0));
    }

    private static RoiStats extractCombinedRoiStats(
            Mat bgrFrame,
            FrameRois rois,
            RoiMode roiMode,
            double foreheadWeight,
            double leftCheekWeight,
            double rightCheekWeight
    ) {
        RoiStats forehead = extractSingleRoiStats(bgrFrame, rois.forehead());
        if (roiMode == RoiMode.FOREHEAD_ONLY) {
            return forehead;
        }

        RoiStats leftCheek = extractSingleRoiStats(bgrFrame, rois.leftCheek());
        RoiStats rightCheek = extractSingleRoiStats(bgrFrame, rois.rightCheek());
        double[] normalized = normalizedRoiWeights(foreheadWeight, leftCheekWeight, rightCheekWeight);

        double meanR = forehead.meanR() * normalized[0] + leftCheek.meanR() * normalized[1] + rightCheek.meanR() * normalized[2];
        double meanG = forehead.meanG() * normalized[0] + leftCheek.meanG() * normalized[1] + rightCheek.meanG() * normalized[2];
        double meanB = forehead.meanB() * normalized[0] + leftCheek.meanB() * normalized[1] + rightCheek.meanB() * normalized[2];
        return new RoiStats(meanR, meanG, meanB);
    }

    private static double[] normalizedRoiWeights(
            double foreheadWeight,
            double leftCheekWeight,
            double rightCheekWeight
    ) {
        double wf = Math.max(0.0, foreheadWeight);
        double wl = Math.max(0.0, leftCheekWeight);
        double wr = Math.max(0.0, rightCheekWeight);
        double sum = wf + wl + wr;
        if (sum <= 1e-9) {
            return new double[]{0.30, 0.35, 0.35};
        }
        return new double[]{wf / sum, wl / sum, wr / sum};
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
            Rect foreheadRect,
            Rect leftCheekRect,
            Rect rightCheekRect,
            double avgG,
            double bpm,
            double rawBpm,
            BpmStatus bpmStatus,
            SignalMethod activeSignalMethod,
            AutoModeState autoModeState,
            SignalMethod probeCandidate,
            double probeSecondsRemaining,
            ProcessingStatus processingStatus,
            double motionScore,
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
            if (foreheadRect != null) {
                rectangle(view, foreheadRect, new Scalar(255, 255, 0, 0), 2, LINE_8, 0);
            }
            if (leftCheekRect != null) {
                rectangle(view, leftCheekRect, new Scalar(255, 128, 0, 0), 2, LINE_8, 0);
            }
            if (rightCheekRect != null) {
                rectangle(view, rightCheekRect, new Scalar(255, 128, 0, 0), 2, LINE_8, 0);
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
                    "FPS: %.1f  Fill: %.1f%%  avgG: %.1f  Method: %s  ROI: %s",
                    fps,
                    windowFill,
                    avgG,
                    activeSignalMethod,
                    config.roiMode()
            );
            String line3 = String.format(
                    Locale.US,
                    "Auto: %s  Probe: %s (%.1fs)  Proc: %s",
                    autoModeState,
                    probeCandidate == null ? "none" : probeCandidate.name(),
                    Math.max(0.0, probeSecondsRemaining),
                    processingStatus
            );
            String line4 = String.format(
                    Locale.US,
                    "Motion: %.3f  %s",
                    Math.max(0.0, motionScore),
                    warnings == null || warnings.isEmpty() ? "Warnings: none" : "Warnings: " + String.join(", ", warnings)
            );
            putText(view, line1, new Point(12, 24), FONT_HERSHEY_SIMPLEX, 0.6, new Scalar(0, 255, 255, 0), 2, LINE_AA, false);
            putText(view, line2, new Point(12, 48), FONT_HERSHEY_SIMPLEX, 0.55, new Scalar(0, 255, 255, 0), 2, LINE_AA, false);
            putText(view, line3, new Point(12, 72), FONT_HERSHEY_SIMPLEX, 0.55, new Scalar(0, 200, 255, 0), 2, LINE_AA, false);
            putText(view, line4, new Point(12, 96), FONT_HERSHEY_SIMPLEX, 0.55, new Scalar(0, 200, 255, 0), 2, LINE_AA, false);

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
    private static final class FrameRois {
        private final Rect forehead;
        private final Rect leftCheek;
        private final Rect rightCheek;

        private FrameRois(Rect forehead, Rect leftCheek, Rect rightCheek) {
            this.forehead = forehead;
            this.leftCheek = leftCheek;
            this.rightCheek = rightCheek;
        }

        private Rect forehead() {
            return forehead;
        }

        private Rect leftCheek() {
            return leftCheek;
        }

        private Rect rightCheek() {
            return rightCheek;
        }

        private boolean isUsable(RoiMode roiMode) {
            if (!isValidRect(forehead)) {
                return false;
            }
            if (roiMode == RoiMode.FOREHEAD_ONLY) {
                return true;
            }
            return isValidRect(leftCheek) && isValidRect(rightCheek);
        }
    }

    @Getter
    @ToString
    @EqualsAndHashCode
    private static final class WarningLogState {
        private boolean noFaceActive;
        private boolean lowQualityActive;
        private boolean tooMuchMotionActive;
        private long lastNoFaceWarnLogNs = Long.MIN_VALUE;
        private long lastLowQualityWarnLogNs = Long.MIN_VALUE;
        private long lastMotionWarnLogNs = Long.MIN_VALUE;
        private long lastFrameDebugLogNs = Long.MIN_VALUE;
    }
}
