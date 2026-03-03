# rppg-java (MVP skeleton)

Minimal Java/Gradle skeleton for rPPG signal processing.

## Stack
- Java 25 runtime (bytecode level is capped for Spring compatibility; on JDK 25 it compiles as Java 24 bytecode)
- Gradle
- Spring Boot
- JUnit 5 (tests only)

## Run tests
- `./gradlew test`

## Package and run JAR (Java 25)
- Build a runnable Spring Boot jar:
  - `./gradlew bootJar`
- Run packaged app:
  - `java -jar build/libs/rppg-java-0.1.0-SNAPSHOT.jar --web`
  - You can also use `--run` or `--camera-check`.
- Use Java 25 runtime to run the packaged jar.

## Lombok
- Lombok is enabled for main and test source sets via annotation processing.
- Repo-level `lombok.config` uses conservative defaults and flags risky usage such as `@SneakyThrows`.

## Single entry point
- Main class: `com.example.rppg.RppgApplication`
- Modes are selected by CLI flags in this one entry point:
  - `--web`
  - `--run`
  - `--camera-check`

## Camera smoke-test (manual)
- Preferred: `./gradlew bootRun --args="--camera-check"`
- Also works: `./gradlew run --args="--camera-check"`
- Opens a live preview for about 5 seconds using the default camera.
- Prints measured FPS and frame size (`WxH`).
- Draws overlays when a face is detected:
  - green rectangle: detected face
  - cyan rectangle: forehead ROI
- If the camera is unavailable, exits gracefully with a clear error message.

## Run mode (manual)
- Preferred: `./gradlew bootRun --args="--run"`
- Also works: `./gradlew run --args="--run"`
- Optional CSV path override:
  - `./gradlew bootRun --args="--run --csv=./logs/custom.csv"`
- Uses face detection and forehead ROI each frame.
- Computes average green channel (`avgG`) from ROI and prints:
  - `avgG=...`
  - `windowFill=...%`
- Builds a fixed-length `SignalWindow` from measured FPS:
  - `windowSamples = round(windowSeconds * measuredFps)` (default `windowSeconds=30`)
- When the window is full, calls `HeartRateEstimator` and prints `BPM update` about every 2 seconds.
- If the signal is flat/invalid, prints `BPM update: invalid: ...` without crashing.
- Computes a simple quality score (`0..1`) from spectral peak dominance.
- If quality is below threshold (default `0.20`), BPM is marked low-confidence/invalid.
- CSV logging in run mode:
  - default path: `./logs/rppg.csv` (directory auto-created)
  - columns: `timestamp,avgG,bpm,quality`
  - no raw frames/video are written.
- Warning heuristics (`warnings` array in snapshots):
  - `NO_FACE`: no face detected for at least `2.0s` (`Config.noFaceWarningSeconds`)
  - `LOW_QUALITY`: signal window is full and quality is below `0.20` (`Config.qualityThreshold`)
  - `LOW_LIGHT`: ROI mean brightness is below `45.0` (`Config.lowLightBrightnessThreshold`)
- `TOO_MUCH_MOTION`: normalized face center shift is above `0.08` or face area change is above `0.25`
    (`Config.motionCenterThreshold`, `Config.motionAreaChangeThreshold`)
- `--run` mode is headless (no web UI server).

## Web UI mode (manual)
- Preferred: `./gradlew bootRun --args="--web"`
- Also works: `./gradlew run --args="--web"`
- Starts Spring Boot server on `http://localhost:8080`.
- `GET /` serves a single-page dashboard with:
  - BPM, quality, FPS, windowFill, warnings
  - Start / Stop / Reset buttons
  - live preview image from `/api/video.mjpg`
- `GET /api/sse` streams JSON snapshots using Server-Sent Events.
- `POST /api/control/start|stop|reset` updates in-memory UI state and returns 200.
- `GET /api/video.mjpg` streams multipart MJPEG (`boundary=frame`) from latest engine frames.
- `start` launches the reusable `RppgEngine`; SSE then emits real engine snapshots (`bpm/quality/fps/windowFill/warnings`).
- In web mode, `start` creates a new session CSV with local timestamp:
  - `logs/session-YYYYMMDD-HHMMSS.csv`
  - if a file with the same second exists, a numeric suffix is appended.
  - header is written once: `timestamp,avgG,bpm,quality`
- Session snapshot fields exposed via SSE:
  - `sessionFilePath`
  - `sessionDurationSec`
  - `sessionRowCount`
- `stop` closes camera/processing thread cleanly; `reset` clears counters and signal window state.
- `stop` flushes/closes the current session CSV; next `start` creates a new file.
- JPEG rendering/encoding is performed inside `RppgEngine` and throttled to about `10 fps` by default (`Config.previewJpegFps`).
- If engine is not started, video endpoint returns `409` and UI shows a clear message.
- The dashboard highlights active warnings in a dedicated panel.

## Haar Cascade File
- Required file path:
  - `src/main/resources/cascades/haarcascade_frontalface_default.xml`
- The project does not download this file automatically.
- If missing, `--camera-check` fails with a clear message and expected path.
- Place a valid OpenCV Haar cascade XML at that path (for example, from a local OpenCV installation).

## Notes
- No camera access is required for tests.
- Signal-processing tests use synthetic sine signals.
- Warning flags are best-effort engineering heuristics, not medical diagnostics.
- JavaCV uses native platform binaries (camera/FFmpeg/OpenCV). `javacv-platform` bundles common natives, but camera behavior is still OS/driver dependent and must be validated on each target platform.
