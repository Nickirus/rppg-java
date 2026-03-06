# rppg-java (MVP skeleton)

Minimal Java/Gradle skeleton for rPPG signal processing.

## Stack
- Java 25 runtime (bytecode level is capped for Spring compatibility; on JDK 25 it compiles as Java 24 bytecode)
- Gradle
- Spring Boot
- JUnit 5 (tests only)

## Docs
- [Architecture](docs/architecture.md)
- [Algorithm](docs/algorithm.md)

## Run tests
- `./gradlew test`
- `./gradlew check` also runs a minimal quality gate that fails if `System.out` or `System.err` is used in `src/main`.

## Offline session evaluator
- Compute summary metrics from a session CSV:
  - `./gradlew evaluateSession "-Pcsv=logs/session-YYYYMMDD-HHMMSS.csv"`
- Report includes:
  - `durationSec`, `validRatio`, `posUsageRatio`, `freezeRatio`
  - `meanBpmValid`, `stdBpmValid`, `jumpRate`, `timeToStableSec`

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
  - `--rtp-ingest`

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
- Optional illumination compensation (`rppg.illum.enabled`):
  - uses a background ROI near the face (non-overlapping) to estimate common brightness changes
  - applies rolling linear regression and subtracts the common-mode component from scalar rPPG sample
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
  - `LOW_SKIN_COVERAGE`: skin pixel coverage in ROI is below `rppg.skin.min-coverage`
- `TOO_MUCH_MOTION`: motion freeze is active based on `rppg.motion.threshold`,
    `rppg.motion.freeze-min-ms`, `rppg.motion.reset-after-ms`
- `--run` mode is headless (no web UI server).

## RTP ingest mode (manual, Janus forward decode)
- Purpose: receive forwarded RTP video locally and decode frames via JavaCV/FFmpeg.
- Preferred:
  - `./gradlew bootRun --args="--rtp-ingest --rtp-video-port=5004 --rtp-codec=auto --rtp-width=640 --rtp-height=480 --rtp-fps=30"`
- Also works:
  - `./gradlew run --args="--rtp-ingest --rtp-video-port=5004"`
- Optional args:
  - `--rtp-port=<port>` alias of `--rtp-video-port`
  - `--rtp-audio-port=<port>` optional audio m-line in generated SDP
  - `--rtp-codec=auto|h264|vp8` (`auto` tries H264 first, then VP8 fallback)
  - `--rtp-width=<pixels>` default `640`
  - `--rtp-height=<pixels>` default `480`
  - `--rtp-fps=<value>` default `30`
  - `--rtp-auto-probe-seconds=<sec>` default `6`
  - `--rtp-duration-seconds=<sec>` default `0` (run until stopped)
- Runtime logs:
  - frame count and measured decode FPS every ~2 seconds.
- Typical flow:
  1. Start Janus publisher and `rtp_forward`.
  2. Start this mode on matching local RTP video port.
  3. Confirm logs show increasing `frames=` and stable `fps=`.

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
- Session CSV includes analysis fields for motion/skin/illumination:
  - `motionScore`, `smoothedRectDelta`, `skinCoverage`, `bgLuma`, `regressionCoeff`

## Configuration (`application.yml`)
- Runtime defaults are in `src/main/resources/application.yml` under `rppg.*`.
- Key entries:
  - `rppg.signal.method` (`AUTO|POS|CHROM|GREEN`)
  - `rppg.signal.quality-mode` (`SNR|PEAK_DOMINANCE|QUALITY2`, default `SNR`)
  - `rppg.signal.quality2.*` for SNR/margin/harmonic thresholds and weights used by `QUALITY2`
  - `rppg.signal.temporal-normalization.enabled`, `rppg.signal.temporal-normalization.eps`
  - `rppg.hr.min-hz`, `rppg.hr.max-hz`
  - `rppg.face.smoothing.alpha`, `rppg.face.smoothing.max-step`
  - `rppg.window.seconds`, `rppg.window.update-interval-ms`
  - `rppg.signal.quality-threshold`, `rppg.signal.max-step-per-update-bpm`
  - `rppg.skin.enabled`, `rppg.skin.min-coverage`, `rppg.skin.fallback-to-unmasked`
  - `rppg.illum.enabled`, `rppg.illum.regression-window-seconds`
  - `rppg.motion.threshold`, `rppg.motion.freeze-min-ms`, `rppg.motion.reset-after-ms`
  - `rppg.roi.mode`, `rppg.roi.forehead-weight`, `rppg.roi.left-cheek-weight`, `rppg.roi.right-cheek-weight`
  - `rppg.auto.*` for fallback/probe thresholds and cooldowns (`rppg.auto.use-quality2-for-gating` enables `quality2` in AUTO decisions)
- Existing CLI CSV override is preserved for run mode:
  - `./gradlew bootRun --args="--run --csv=./logs/custom.csv"`

## Haar Cascade File
- Required file path:
  - `src/main/resources/cascades/haarcascade_frontalface_default.xml`
- The project does not download this file automatically.
- If missing, `--camera-check` fails with a clear message and expected path.
- Place a valid OpenCV Haar cascade XML at that path (for example, from a local OpenCV installation).

## Local Janus VideoRoom Gateway (Docker Compose)
- Files:
  - `docker-compose.yml`
  - `janus/etc/janus.jcfg`
  - `janus/etc/janus.plugin.videoroom.jcfg`
  - `janus/publisher/index.html`
  - `janus/publisher/nginx.conf`
- Services:
  - `janus` (Janus Gateway + VideoRoom plugin)
  - `janus-publisher` (minimal publisher page + reverse proxy to Janus API)
- Default ports:
  - Publisher page: `http://localhost:8081`
  - Janus API: `http://localhost:8088/janus`
  - Janus WebSockets: `ws://localhost:8188`
  - RTP/RTCP UDP range: `10000-10100/udp`

Run locally:
1. Ensure Docker Desktop/Engine is running.
2. Start stack:
   - `docker compose up -d`
3. Open publisher page:
   - `http://localhost:8081`
4. Click `Start publish` and allow camera/microphone access.
5. Verify successful publish:
   - Page status switches to `Publishing` or `Publishing (webrtcup)`.
   - Page log contains `WebRTC is up on Janus side.` and `Publish completed.`
   - Optional: click `List participants` and confirm your publisher appears in room `1234`.
   - Optional backend check: `docker compose logs -f janus`
6. Stop stream from UI (`Stop`) or stop stack:
   - `docker compose down`

Notes:
- This setup is local-machine oriented (`nat_1_1_mapping = 127.0.0.1` in Janus config).
- If media does not flow, check local firewall rules for UDP `10000-10100`.

### Janus RTP Forward Smoke Test
- Helper script:
  - `scripts/janus_rtp_forward_smoke.py`
- What it does:
  - creates Janus session
  - attaches `janus.plugin.videoroom`
  - finds current publisher in room
  - starts `rtp_forward` to target host/UDP ports
  - counts incoming UDP packets on the configured local ports
  - stops forwarder and destroys Janus session

Required before running:
1. `docker compose up -d`
2. Open `http://localhost:8081` and click `Start publish` (publisher must be active).

One-command smoke test (PowerShell):
- `python scripts/janus_rtp_forward_smoke.py --janus-url http://localhost:8088/janus --room 1234 --host host.docker.internal --audio-port 5002 --video-port 5004 --duration 15`

Expected success output:
- non-zero packet counts on at least one of the RTP ports
- final line: `Smoke test OK: RTP packets observed.`

Common parameters:
- `--janus-url` Janus REST endpoint (default `http://localhost:8088/janus`)
- `--room` VideoRoom id (default `1234`)
- `--publisher-id` explicit publisher feed id (optional; otherwise first participant is used)
- `--host` forward target host reachable from Janus container
  - Docker Desktop: `host.docker.internal`
  - Linux engine: often bridge host like `172.17.0.1`
- `--audio-port`, `--video-port` local UDP ports to receive RTP
- `--duration` packet counting window in seconds
- `--admin-key` optional VideoRoom `admin_key` if your room requires it

## Notes
- No camera access is required for tests.
- Signal-processing tests use synthetic sine signals.
- Warning flags are best-effort engineering heuristics, not medical diagnostics.
- Face jitter handling: ROI uses smoothed face rectangle (EMA), while motion gating uses raw detected face rectangle for conservative motion detection.
- JavaCV uses native platform binaries (camera/FFmpeg/OpenCV). `javacv-platform` bundles common natives, but camera behavior is still OS/driver dependent and must be validated on each target platform.
