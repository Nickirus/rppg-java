# Algorithm

This document describes the current implementation in `com.example.rppg.app.RppgEngine` and related classes.

## 1) Face Detection and faceRect Smoothing

- Face detection runs in `RppgEngine.detectLargestFace(...)` using OpenCV Haar cascade (`CascadeClassifier`).
- Cascade file path is fixed to:
  - `src/main/resources/cascades/haarcascade_frontalface_default.xml`
- Per frame flow:
  - Convert BGR frame to grayscale.
  - `equalizeHist`.
  - `detectMultiScale(gray, faces, 1.1, 3, 0, minSize=80x80, maxSize=unbounded)`.
  - Pick largest detected rectangle.
- Detection cadence is currently every frame (`DETECT_EVERY_N_FRAMES = 1`).

Smoothing:

- Implemented by `com.example.rppg.vision.FaceRectSmoother`.
- Config:
  - `rppg.face.smoothing.alpha` (default `0.2`)
  - `rppg.face.smoothing.max-step` (default `0.2`)
- Uses EMA-like update on center and size, with per-step clamp relative to previous size.
- Important split:
  - Motion gating uses **raw** face rectangle (`MotionGate.update(rawFaceRect, ...)`).
  - ROI selection uses **smoothed** face rectangle (if available).

## 2) ROI Selection (Multi-ROI) and Weights

ROI geometry is in `com.example.rppg.vision.RoiSelector`.

Relative rectangles from `faceRect`:

- Forehead: `x=0.20, y=0.15, w=0.60, h=0.20`
- Left cheek: `x=0.14, y=0.48, w=0.28, h=0.22`
- Right cheek: `x=0.58, y=0.48, w=0.28, h=0.22`

ROI modes (`com.example.rppg.vision.RoiMode`):

- `FOREHEAD_ONLY`
- `MULTI` (forehead + both cheeks, default)

Config:

- `rppg.roi.mode` (default `MULTI`)
- `rppg.roi.forehead-weight` (default `0.30`)
- `rppg.roi.left-cheek-weight` (default `0.35`)
- `rppg.roi.right-cheek-weight` (default `0.35`)

Weights are normalized in `RppgEngine.normalizedRoiWeights(...)`.
If weight sum is near zero, fallback weights are `0.30 / 0.35 / 0.35`.

## 3) Skin Mask + skinCoverage Gating

Per-ROI color statistics are computed by `com.example.rppg.vision.SkinMaskRoiAnalyzer`.

- Input is BGR `Mat` of ROI.
- Skin mask is computed in YCrCb using `inRange`:
  - lower = `(0, 133, 77)`
  - upper = `(255, 173, 127)`
- `skinCoverage = skinPixels / totalPixels` in `[0..1]`.
- BGR mean is mapped to `RoiStats(meanR, meanG, meanB)` as:
  - `R = mean[2], G = mean[1], B = mean[0]`.

Config:

- `rppg.skin.enabled` (default `true`)
- `rppg.skin.min-coverage` (default `0.25`)
- `rppg.skin.fallback-to-unmasked` (default `true`)

Behavior:

- If skin is disabled: use unmasked mean, `skinCoverage=1.0`.
- If enabled and `skinCoverage >= min-coverage`: use masked mean.
- If enabled and `skinCoverage < min-coverage`:
  - if `fallback-to-unmasked=true`: use unmasked mean.
  - else: keep masked path; if mask has zero skin pixels, ROI stats become zeros.

Warning:

- `LOW_SKIN_COVERAGE` is emitted when skin is enabled and `skinCoverage < rppg.skin.min-coverage`.

## 4) Signal Extraction: GREEN, POS, CHROM, AUTO

Interface:

- `com.example.signal.RppgSignalExtractor`
- Implementations:
  - `GreenExtractor`
  - `PosExtractor`
  - `ChromExtractor`

Methods (`com.example.signal.SignalMethod`):

- `GREEN`: scalar = `meanG`.
- `POS`: temporal RGB projection with internal temporal window.
- `CHROM`: chrominance projection with internal temporal window.
- `AUTO`: runtime method selected by `AutoSignalMethodSelector`.

Extractor temporal config:

- `rppg.signal.extractor-temporal-window` (default `32`)

### AUTO switching logic (`com.example.signal.AutoSignalMethodSelector`)

Priority:

- Preferred: `POS`, then `CHROM`, then `GREEN`.

State (`com.example.signal.AutoModeState`):

- `STABLE`, `FALLBACK`, `PROBING`.

Fallback triggers (when configured method is `AUTO`):

- Sustained bad BPM status (`HOLDING` or `INVALID`) for at least:
  - `rppg.auto.fallback-min-hold-seconds` (default `8.0`)
- Or consecutive low-quality updates reaches:
  - `rppg.auto.low-quality-updates-threshold` (default `3`)
- Fallback switch is allowed only when switch cooldown elapsed:
  - `rppg.auto.switch-cooldown-seconds` (default `8.0`)

Fallback chain:

- `POS -> CHROM -> GREEN`

Recovery (probe-based):

- If current active method is not `POS`, and update is good (`bpmStatus=VALID` and quality above threshold), and recovery cooldown elapsed:
  - `rppg.auto.recovery-cooldown-seconds` (default `20.0`)
- Start probe with candidate:
  - `GREEN -> CHROM`
  - `CHROM -> POS`
- Probe duration:
  - `rppg.auto.probe-window-seconds` (default `12.0`)
- Probe success criteria:
  - valid ratio `>= rppg.auto.probe-valid-ratio-threshold` (default `0.60`)
  - average quality `>= quality-threshold + rppg.auto.probe-quality-margin` (default margin `0.0`)
- On probe success: switch active method to candidate.
- On probe fail: revert to previous active method.
- After probe ends, recovery cooldown is restarted.

Quality source for AUTO gates:

- `autoQuality = quality2` if `rppg.auto.use-quality2-for-gating=true` (default `true`).
- Otherwise AUTO uses current selected `quality` metric.

When effective method changes, engine resets extractor/window state.

## 5) Temporal Normalization + Band-Pass Filtering

Core preprocessing (used by `HeartRateEstimator` and `SignalQualityScorer`):

1. `Preprocessor.temporalNormalizeByMean(x, eps)` (optional by config)
   - `x_norm = (x - mean(x)) / max(eps, mean(x))`
2. `Preprocessor.detrendAndNormalize(x)`
   - subtract mean
   - divide by std if std is not near zero
3. `Preprocessor.bandPass(x, fs, minHz, maxHz)`
   - deterministic RC high-pass + low-pass
   - two cascaded stages for stronger attenuation
4. `Preprocessor.applyHannWindowInPlace(x)`

Config:

- `rppg.signal.temporal-normalization.enabled` (default `true`)
- `rppg.signal.temporal-normalization.eps` (default `1e-6`)
- HR band:
  - `rppg.hr.min-hz` (default `0.8`)
  - `rppg.hr.max-hz` (default `2.5`)

## 6) FFT, Windowing, and Overlapping Update Cadence

Signal buffering:

- `com.example.signal.SignalWindow`
- Capacity is initialized from measured runtime FPS:
  - `round(rppg.window.seconds * measuredFps)`
- `rppg.window.seconds` default is `30`.

Overlapping update cadence:

- The window is sliding (new sample each frame).
- BPM/quality estimation is run only when:
  - window is full, and
  - at least `rppg.window.update-interval-ms` elapsed since previous estimate.
- Default `rppg.window.update-interval-ms = 2000`.
- Each estimate uses the **full current window** (overlapping windows).

FFT:

- `com.example.signal.FftPowerSpectrum` uses JTransforms:
  - `org.jtransforms.fft.DoubleFFT_1D.realForwardFull`.
- Power spectrum:
  - `P[k] = Re[k]^2 + Im[k]^2`
  - bins `k = 0..N/2`.

## 7) Peak Picking + Quadratic Interpolation

Peak detection:

- `PeakPicker.argMaxInBand(power, fsHz, minHz, maxHz)` finds max bin inside HR band.

Sub-bin refinement:

- `PeakPicker.refineParabolicBin(power, kPeak)` applies quadratic (parabolic) interpolation using bins `k-1, k, k+1`.
- `HeartRateEstimator` converts refined bin to frequency:
  - `hz = PeakPicker.binToHz(refinedBin, N, fsHz)`
  - `bpm = hz * 60`.

Invalid result conditions include:

- too short signal
- non-finite samples
- flat filtered signal
- no peak in band
- peak power too low
- out-of-band/invalid refined frequency

## 8) Quality Score (SNR, PEAK_DOMINANCE, QUALITY2)

Implemented in `com.example.signal.SignalQualityScorer`.

`rppg.signal.quality-mode`:

- `SNR` (default)
- `PEAK_DOMINANCE`
- `QUALITY2`

Common spectrum analysis:

- HR-band peak is found.
- Band totals and noise are computed excluding bins `peak+/-1`.

Metrics:

- `peakDominance = peakPower / totalBandPower` (clamped `[0..1]`)
- `snr = peakPower / mean(noiseFloorBins)`
- `margin = peakPower / secondPeakPower` (second peak excludes `peak+/-1`)
- `harmonicRatio = maxPower(2*peak+/-1) / peakPower`

`QUALITY2` mapping:

- Converts SNR/margin/harmonic metrics to `[0..1]` by linear thresholds.
- Weighted average with config weights.

Config (defaults):

- `rppg.signal.quality-threshold = 0.20`
- `rppg.signal.quality2.snr-low = 2.0`
- `rppg.signal.quality2.snr-high = 8.0`
- `rppg.signal.quality2.margin-low = 1.1`
- `rppg.signal.quality2.margin-high = 2.5`
- `rppg.signal.quality2.harmonic-enabled = true`
- `rppg.signal.quality2.harmonic-low = 0.05`
- `rppg.signal.quality2.harmonic-high = 0.35`
- `rppg.signal.quality2.snr-weight = 0.6`
- `rppg.signal.quality2.margin-weight = 0.3`
- `rppg.signal.quality2.harmonic-weight = 0.1`

Notes:

- Engine publishes `quality` as the value from `SignalQualityScorer.quality(..., quality-mode, ...)`.
- Engine also computes `quality2` internally for AUTO gating when enabled.

## 9) Stabilizer (Hold-Last-Good + Clamp)

`com.example.signal.BpmStabilizer`:

- Maintains `lastGoodBpm` and `lastGoodHz`.
- On valid raw estimate + accepted quality:
  - update as `VALID`.
  - if jump from last good exceeds `rppg.signal.max-step-per-update-bpm` (default `8.0`):
    - clamp step to max allowed,
    - mark status `HOLDING`.
- On invalid raw or low quality:
  - if last good exists -> keep it with `HOLDING`.
  - else -> `INVALID`.

Snapshot fields:

- `bpm` = stabilized BPM
- `rawBpm` = un-stabilized estimate (if available)
- `bpmStatus` = `VALID | HOLDING | INVALID`

## 10) Motion Gating (Freeze) + Window Reset

`com.example.rppg.app.MotionGate` computes per-frame motion:

- `centerShiftNorm = hypot(dx,dy) / max(prevFaceW, prevFaceH)`
- `areaChangeNorm = abs(currArea - prevArea) / prevArea`
- `motionScore = max(centerShiftNorm, areaChangeNorm)`

Config:

- `rppg.motion.threshold` (default `0.10`)
- `rppg.motion.freeze-min-ms` (default `300`)
- `rppg.motion.reset-after-ms` (default `3000`)

Behavior:

- If motion score stays above threshold long enough, status becomes `MOTION_FREEZE`.
- While frozen:
  - sampling into `SignalWindow` is paused.
  - stabilizer holds current BPM.
  - warning `TOO_MUCH_MOTION` is emitted.
- If freeze lasts past reset timeout:
  - `SignalWindow` is reinitialized.
  - warmup samples are cleared.
  - illumination compensator state is reset.
  - AUTO selector timers are reset (`resetTimers`).
  - stabilizer keeps/holds last good output.

## 11) Illumination Compensation with Background ROI

Implemented by `com.example.signal.IlluminationCompensator`.

Background ROI selection (`RppgEngine.selectBackgroundRoi`):

- Candidate regions near face: right, then left, then above, then below.
- Must be in-frame and non-overlapping with face ROI.

Per-frame:

- `bgLuma` = mean brightness of selected background ROI.
- If enabled and finite:
  - update rolling regression window and estimate coefficient `beta`.
  - compensated sample = `x - beta * bg`.

Config:

- `rppg.illum.enabled` (default `true`)
- `rppg.illum.regression-window-seconds` (default `30`)

Compensator window length in samples is derived from config and target fps in engine.

## Output/Observability Relevant to Algorithm

`com.example.rppg.app.RppgSnapshot` fields used by UI/SSE include:

- `avgG`, `bpm`, `rawBpm`, `bpmStatus`
- `activeSignalMethod`, `autoModeState`, `probeCandidate`, `probeSecondsRemaining`
- `processingStatus`, `motionScore`
- `roiMode`, `roiWeights`
- `quality`, `skinCoverage`
- `fps`, `windowFill`
- `warnings`

Session CSV (`CsvSignalLogger`) includes:

- `timestamp,avgG,bpm,quality,rawBpm,bpmStatus,activeSignalMethod,autoModeState,motionScore,smoothedRectDelta,skinCoverage,bgLuma,regressionCoeff,processingStatus,windowFill,fps,peakHz`

## Known Failure Modes

- No face detected:
  - cascade missing/invalid, poor framing, occlusion, backlight.
- Low skin coverage:
  - strong shadows, non-skin background inside ROI, extreme white balance.
- Low light / flicker:
  - low SNR, unstable quality, frequent `HOLDING`.
- Motion bursts:
  - repeated `MOTION_FREEZE`, window resets, delayed re-lock.
- Wrong peak lock:
  - harmonic/subharmonic lock, periodic motion dominating HR band.
- AUTO oscillation pressure:
  - aggressive thresholds/cooldowns can cause frequent fallback/probe cycles.

## Tuning Guide

Low light:

- Increase scene illumination first.
- Tune:
  - `rppg.warning.low-light-brightness-threshold`
  - `rppg.signal.quality-threshold` (slightly lower if too strict)
  - consider `rppg.skin.fallback-to-unmasked=true` (default) to avoid zeroed masked signal.

LED flicker / brightness modulation:

- Keep illumination compensation enabled:
  - `rppg.illum.enabled=true`
- Increase regression memory:
  - `rppg.illum.regression-window-seconds` (e.g., 30 -> 45)
- Verify HR band stays correct:
  - `rppg.hr.min-hz`, `rppg.hr.max-hz`.

Too much motion / frequent freezes:

- If freezes are too sensitive:
  - increase `rppg.motion.threshold`
  - increase `rppg.motion.freeze-min-ms`
- If reset happens too quickly:
  - increase `rppg.motion.reset-after-ms`
- If ROI jitters:
  - increase smoothing (higher `rppg.face.smoothing.alpha` cautiously)
  - reduce `rppg.face.smoothing.max-step` for tighter clamp.

Wrong BPM (persistent offset or sudden jumps):

- Adjust estimator sensitivity:
  - narrow HR band (`rppg.hr.min-hz`, `rppg.hr.max-hz`)
- Improve stability:
  - lower `rppg.signal.max-step-per-update-bpm`
- Improve quality discrimination:
  - switch `rppg.signal.quality-mode` and tune `rppg.signal.quality2.*`.

Frequent `HOLDING` / slow updates:

- Check window cadence:
  - reduce `rppg.window.update-interval-ms` for faster refresh.
- Reduce quality strictness carefully:
  - lower `rppg.signal.quality-threshold` a bit.
- For AUTO behavior:
  - tune `rppg.auto.fallback-min-hold-seconds`
  - tune `rppg.auto.low-quality-updates-threshold`
  - tune `rppg.auto.recovery-cooldown-seconds`
  - tune `rppg.auto.probe-window-seconds` and `rppg.auto.probe-valid-ratio-threshold`
  - verify `rppg.auto.use-quality2-for-gating` choice.
