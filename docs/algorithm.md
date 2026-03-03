# Algorithm

## ROI Geometry
ROI geometry is implemented in `com.example.rppg.vision.RoiSelector`.

Given `faceRect`:
- Forehead: `x=0.20, y=0.15, w=0.60, h=0.20` (relative to face rect).
- Left cheek: `x=0.14, y=0.48, w=0.28, h=0.22`.
- Right cheek: `x=0.58, y=0.48, w=0.28, h=0.22`.

`RoiMode`:
- `FOREHEAD_ONLY`: use forehead only.
- `MULTI`: combine forehead + left cheek + right cheek.

Weights come from:
- `rppg.roi.forehead-weight`
- `rppg.roi.left-cheek-weight`
- `rppg.roi.right-cheek-weight`

Weights are normalized in `RppgEngine.normalizedRoiWeights` (fallback to `0.30/0.35/0.35` if sum is near zero).

## RoiStats and Color Channel Mapping
`RoiStats` has:
- `meanR`
- `meanG`
- `meanB`

In OpenCV frames are BGR. In `RppgEngine.extractSingleRoiStats`:
- `channelMeans.get(0)` is B,
- `channelMeans.get(1)` is G,
- `channelMeans.get(2)` is R.

Mapping to `RoiStats(meanR, meanG, meanB)` is explicit:
- `meanR = get(2)`, `meanG = get(1)`, `meanB = get(0)`.

## Signal Methods
Implemented via `RppgSignalExtractor`:
- `GREEN` (`GreenExtractor`): scalar = `meanG`.
- `POS` (`PosExtractor`):
- keeps temporal RGB deque (`rppg.signal.extractor-temporal-window`)
- normalizes each channel by window mean
- computes `x = 2R - G - B`, `y = 1.5R + G - 1.5B`
- output sample = `x_last + alpha*y_last`, `alpha = std(x)/std(y)`.
- `CHROM` (`ChromExtractor`):
- normalized channels
- `x = 3R - 2G`, `y = 1.5R + G - 1.5B`
- output sample = `x_last - alpha*y_last`.
- `AUTO`: controlled by `AutoSignalMethodSelector`.

AUTO behavior:
- fallback: `POS -> CHROM -> GREEN` when quality/status stays bad.
- recovery with probes: `GREEN -> CHROM -> POS` when signal improves.
- probe quality gates and cooldowns come from `rppg.auto.*`.

## Preprocessing and Band-Pass
`HeartRateEstimator` and `SignalQualityScorer` use:
1. `Preprocessor.detrendAndNormalize`:
- subtract mean
- divide by std (if std is not near zero).
2. `Preprocessor.bandPass`:
- deterministic RC high-pass + low-pass,
- applied in two cascaded stages,
- cutoffs from HR band (`rppg.hr.min-hz`, `rppg.hr.max-hz`).
3. `Preprocessor.applyHannWindowInPlace`.

## FFT and Power Spectrum
`FftPowerSpectrum.powerSpectrum`:
- uses JTransforms `DoubleFFT_1D.realForwardFull`,
- computes power `P[k] = Re[k]^2 + Im[k]^2`,
- returns bins `0..N/2`.

## Peak Picking in HR Band
`PeakPicker.argMaxInBand`:
- converts Hz band to bin range using `fs` and `N`,
- searches max power in `[minHz,maxHz]`.

`HeartRateEstimator` then:
- converts bin to Hz (`PeakPicker.binToHz`)
- BPM = Hz * 60.
- marks invalid for short/non-finite/flat/no-peak/out-of-band cases.

## Quality Score
`SignalQualityScorer.peakDominance`:
- computes power in HR band,
- finds band peak,
- score = `peakPower / totalBandPower`,
- clamps to `[0,1]`.

Primary threshold:
- `rppg.signal.quality-threshold` (default `0.20`).

## Stabilization
`BpmStabilizer`:
- tracks `lastGoodBpm`/`lastGoodHz`.
- If raw BPM valid and quality passes threshold:
- accept update as `VALID`,
- if jump exceeds `rppg.signal.max-step-per-update-bpm`, clamp step and mark `HOLDING`.
- If raw/quality invalid:
- keep last good as `HOLDING` if available,
- else `INVALID`.

During motion freeze, engine calls `holdCurrent()` and does not push new samples.

## Motion Gating
`MotionGate` computes per-frame `motionScore` from face-rect movement/scale change and manages:
- freeze entry (`rppg.motion.freeze-min-ms`),
- reset trigger (`rppg.motion.reset-after-ms`),
- threshold (`rppg.motion.threshold`).

When frozen:
- status is `ProcessingStatus.MOTION_FREEZE`,
- warning `TOO_MUCH_MOTION`,
- sampling pauses.

## Snapshot Fields Relevant to Algorithm
`RppgSnapshot` publishes:
- `avgG`
- `bpm`
- `rawBpm`
- `bpmStatus` (`VALID|HOLDING|INVALID`)
- `activeSignalMethod`
- `autoModeState` (`STABLE|PROBING|FALLBACK`)
- `probeCandidate`
- `probeSecondsRemaining`
- `processingStatus` (`NORMAL|MOTION_FREEZE`)
- `motionScore`
- `roiMode`
- `roiWeights`
- `quality`
- `fps`
- `windowFill`
- `warnings`

These fields drive both SSE (`/api/sse`) and overlay text on MJPEG frames.
