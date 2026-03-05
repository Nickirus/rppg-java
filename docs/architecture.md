# Architecture

## Overview

Single entry point: `com.example.rppg.RppgApplication`.

The runtime core is `com.example.rppg.app.RppgEngine`. It owns camera lifecycle, frame processing, algorithm state, snapshot publication, and JPEG overlay generation. Web and CLI modes orchestrate the engine but do not implement signal processing logic themselves.

## Components and Responsibilities

### Bootstrapping and mode routing

- `com.example.rppg.RppgApplication`
  - Parses CLI args: `--web`, `--run`, `--camera-check`, optional `--csv=...`.
  - `--web`: starts Spring Boot web app on `127.0.0.1:8080`.
  - `--run`: loads `RppgProperties` from `application.yml` using a non-web Spring context, then executes `RunModeProcessor`.
  - `--camera-check`: runs `CameraSmokeCheck`.

### Runtime config

- `com.example.rppg.app.RppgProperties` (`@ConfigurationProperties(prefix="rppg")`)
  - Binds `rppg.*` config groups from `src/main/resources/application.yml`.
  - Converts to immutable `com.example.rppg.app.Config`.

### Engine and processing

- `com.example.rppg.app.RppgEngine`
  - Starts/stops processing worker thread.
  - Captures camera frames via `OpenCVFrameGrabber`.
  - Detects face (`CascadeClassifier`), applies face smoothing (`FaceRectSmoother`), motion gating (`MotionGate`), ROI extraction (`RoiSelector` + `SkinMaskRoiAnalyzer`), method extraction (`RppgSignalExtractor` implementations), signal windowing (`SignalWindow`), estimation (`HeartRateEstimator`), quality (`SignalQualityScorer`), stabilization (`BpmStabilizer`), AUTO selection (`AutoSignalMethodSelector`), CSV logging (`CsvSignalLogger`), and JPEG overlay rendering.

### Vision and ROI layer

- `com.example.rppg.vision.RoiSelector`
  - Forehead and multi-region ROI geometry.
- `com.example.rppg.vision.SkinMaskRoiAnalyzer`
  - Computes masked/unmasked ROI RGB stats and `skinCoverage`.
- `com.example.rppg.vision.FaceRectSmoother`
  - EMA-style smoothing for face rectangle used by ROI selection.

### Signal layer

- Extractors:
  - `GreenExtractor`, `PosExtractor`, `ChromExtractor` (`RppgSignalExtractor`)
- Estimation and quality:
  - `HeartRateEstimator`
  - `SignalQualityScorer`
  - `Preprocessor`
  - `FftPowerSpectrum` (JTransforms)
  - `PeakPicker`
- State/control:
  - `BpmStabilizer`
  - `AutoSignalMethodSelector`
  - `IlluminationCompensator`
  - `SignalWindow`

### Web layer

- `com.example.rppg.web.WebUiController`
  - Serves inline dashboard HTML at `GET /`.
  - Streams SSE snapshots at `GET /api/sse`.
  - Streams MJPEG at `GET /api/video.mjpg`.
  - Control endpoints:
    - `POST /api/control/start`
    - `POST /api/control/stop`
    - `POST /api/control/reset`
- `com.example.rppg.web.WebUiStateService`
  - Owns current engine instance in web mode.
  - Handles session CSV file creation per start.
  - Broadcasts snapshots to connected SSE emitters every second.

### CLI runtime helpers

- `com.example.rppg.app.RunModeProcessor`
  - Headless run loop orchestration for `--run`.
- `com.example.rppg.app.CameraSmokeCheck`
  - Manual preview check mode with face + forehead overlay.

### Offline evaluator

- `com.example.rppg.tools.SessionEvaluator`
  - Reads session CSV and computes aggregate metrics:
    - `durationSec`
    - `validRatio`
    - `posUsageRatio`
    - `freezeRatio`
    - `meanBpmValid`
    - `stdBpmValid`
    - `jumpRate`
    - `timeToStableSec`
- Gradle task:
  - `./gradlew evaluateSession -Pcsv=<path>`

## End-to-End Data Flow (Text Diagram)

Web mode (`--web`):

1. `RppgApplication` starts Spring Boot app.
2. `WebUiStateService` initializes engine from `RppgProperties.toConfig()`.
3. User calls `POST /api/control/start`.
4. `WebUiStateService.start()`:
   - creates `logs/session-YYYYMMDD-HHMMSS.csv`
   - builds session config with `withCsvPath(...)`
   - starts new `RppgEngine`.
5. `RppgEngine.runLoop()` per frame:
   - grab frame -> face detect -> motion gate -> smooth face -> compute ROIs -> compute ROI stats (skin aware) -> extract scalar -> illumination compensation -> push to `SignalWindow` (unless frozen) -> periodic full-window estimate -> quality + stabilizer + AUTO selector -> warnings -> snapshot publish -> CSV tick log -> JPEG encode/store.
6. Read path:
   - `GET /api/sse` sends serialized `RppgSnapshot`.
   - `GET /api/video.mjpg` reads latest JPEG bytes and streams multipart response.
7. User calls `POST /api/control/stop`:
   - engine stops, camera/resources are released, CSV writer closes.

Run mode (`--run`):

1. `RppgApplication` loads `Config` from `application.yml` in non-web context.
2. `RunModeProcessor.run(config)` starts `RppgEngine`.
3. Polls `RppgSnapshot` until completion/timeout/error.
4. Stops engine and exits with status code based on success/failure.

Camera check (`--camera-check`):

1. `CameraSmokeCheck.runDefaultCameraCheck()`.
2. Opens desktop preview (`CanvasFrame`) for ~5 seconds.
3. Draws face + forehead overlays and logs measured FPS/frame size.

## Concurrency Model

### Engine thread model

- `RppgEngine.start()` creates one worker thread (`rppg-engine`) and returns immediately.
- Worker thread runs `runLoop()` until stop requested or fatal failure.
- `RppgEngine.stop()` sets stop flag and joins worker thread (best effort timeout).

### Shared state between engine and web

- `latestSnapshot`: `AtomicReference<RppgSnapshot>`
  - written by engine thread
  - read by web ticker/SSE/controller threads and CLI pollers
- `latestJpegFrame`: `AtomicReference<byte[]>`
  - written by engine thread
  - read by MJPEG endpoint
  - `getLatestJpegFrame()` returns clone for read safety.
- Session counters/path:
  - atomic fields (`AtomicLong`, `AtomicReference<String>`)

### Web service synchronization

- `WebUiStateService` uses:
  - `volatile RppgEngine engine`
  - `engineLock` for start/stop/reset transitions
- Read-only operations (`getSnapshot`, `getLatestJpegFrame`, `isRunning`) do not mutate engine lifecycle.
- Control endpoints mutate lifecycle only through `WebUiStateService` methods under lock.

### SSE and MJPEG behavior

- SSE:
  - `ScheduledExecutorService` (`web-ui-ticker`) broadcasts snapshot every 1 second.
  - Each emitter is managed in `CopyOnWriteArrayList`.
- MJPEG:
  - HTTP handler loops writing latest JPEG chunks.
  - Endpoint never opens camera; it only reads from `RppgEngine`.
  - If engine is not running and no frame exists: returns `409`.

## Runtime Modes and Packaging

### Gradle run modes

- `./gradlew bootRun --args="--web"`
- `./gradlew bootRun --args="--run"`
- `./gradlew bootRun --args="--camera-check"`

Also configured:

- `./gradlew run --args="--web|--run|--camera-check"`

Main class for both application and Spring Boot plugins:

- `com.example.rppg.RppgApplication`

### Packaging

- Build runnable Spring Boot jar:
  - `./gradlew bootJar`
- Run packaged jar:
  - `java -jar build/libs/rppg-java-0.1.0-SNAPSHOT.jar --web`
  - `--run` and `--camera-check` are also supported.

## Configuration Surface (`rppg.*`)

Bound by `RppgProperties` and converted to `Config`.

- `rppg.camera.*`
  - `index`, `width`, `height`, `target-fps`, `preview-jpeg-fps`
- `rppg.face.smoothing.*`
  - `alpha`, `max-step`
- `rppg.window.*`
  - `seconds`, `update-interval-ms`
- `rppg.hr.*`
  - `min-hz`, `max-hz`
- `rppg.csv.path`
- `rppg.signal.*`
  - `quality-mode`, `method`, `extractor-temporal-window`, `quality-threshold`, `max-step-per-update-bpm`, `temporal-normalization.*`, `quality2.*`
- `rppg.skin.*`
  - `enabled`, `min-coverage`, `fallback-to-unmasked`
- `rppg.illum.*`
  - `enabled`, `regression-window-seconds`
- `rppg.roi.*`
  - `mode`, `forehead-weight`, `left-cheek-weight`, `right-cheek-weight`
- `rppg.auto.*`
  - `fallback-min-hold-seconds`, `low-quality-updates-threshold`, `switch-cooldown-seconds`, `recovery-cooldown-seconds`, `probe-window-seconds`, `probe-valid-ratio-threshold`, `probe-quality-margin`, `use-quality2-for-gating`
- `rppg.warning.*`
  - `no-face-seconds`, `low-light-brightness-threshold`
- `rppg.motion.*`
  - `threshold`, `freeze-min-ms`, `reset-after-ms`

## Observability

### Logs (SLF4J)

- Main code paths use `@Slf4j` logging.
- INFO:
  - application mode selection, web startup, engine start/stop/reset, session CSV creation/close.
- WARN:
  - camera unavailable, missing/invalid cascade, sustained warnings (`NO_FACE`, `LOW_QUALITY`, `LOW_SKIN_COVERAGE`, `TOO_MUCH_MOTION`), SSE/MJPEG stream issues.
- DEBUG:
  - throttled per-frame telemetry and BPM update diagnostics.

### Snapshots and live telemetry

- `RppgSnapshot` is the canonical runtime DTO for SSE and UI state.
- Includes algorithm/runtime fields such as:
  - `avgG`, `bpm`, `rawBpm`, `bpmStatus`
  - `activeSignalMethod`, `autoModeState`, `probeCandidate`, `probeSecondsRemaining`
  - `processingStatus`, `motionScore`
  - `roiMode`, `roiWeights`, `quality`, `skinCoverage`
  - `fps`, `windowFill`, `warnings`
  - `sessionFilePath`, `sessionDurationSec`, `sessionRowCount`

### CSV logging

- Engine logger: `CsvSignalLogger`.
- Header:
  - `timestamp,avgG,bpm,quality,rawBpm,bpmStatus,activeSignalMethod,autoModeState,motionScore,smoothedRectDelta,skinCoverage,bgLuma,regressionCoeff,processingStatus,windowFill,fps,peakHz`
- Web mode:
  - new session file per `start`: `logs/session-YYYYMMDD-HHMMSS.csv` (with suffix if collision).
- Run mode:
  - uses `rppg.csv.path` or `--csv=...`.

### Offline evaluation

- `SessionEvaluator` prints a report table from session CSV logs.
- Command:
  - `./gradlew evaluateSession -Pcsv=logs/session-...csv`
