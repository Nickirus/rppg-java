# rppg-java (MVP skeleton)

Minimal Java/Gradle skeleton for rPPG signal processing.

## Stack
- Java 25 target (falls back to the highest local JDK when Java 25 is unavailable)
- Gradle
- JUnit 5 (tests only)

## Run tests
- `./gradlew test`

## Camera smoke-test (manual)
- `./gradlew run --args="--camera-check"`
- Opens a live preview for about 5 seconds using the default camera.
- Prints measured FPS and frame size (`WxH`).
- Draws overlays when a face is detected:
  - green rectangle: detected face
  - cyan rectangle: forehead ROI
- If the camera is unavailable, exits gracefully with a clear error message.

## Run mode (manual)
- `./gradlew run --args="--run"`
- Optional CSV path override:
  - `./gradlew run --args="--run --csv=./logs/custom.csv"`
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

## Haar Cascade File
- Required file path:
  - `src/main/resources/cascades/haarcascade_frontalface_default.xml`
- The project does not download this file automatically.
- If missing, `--camera-check` fails with a clear message and expected path.
- Place a valid OpenCV Haar cascade XML at that path (for example, from a local OpenCV installation).

## Notes
- No camera access is required for tests.
- Signal-processing tests use synthetic sine signals.
