# AGENTS.md — rppg-java

## Project goal (MVP)
Build a local-only Java app that estimates heart rate (BPM) from face video:
face ROI -> green channel average -> preprocess -> spectrum peak in HR band -> BPM.
This is a demo/estimation, NOT a medical device.

## Non-goals
- No medical claims, no clinical accuracy statements.
- No cloud/network calls. No uploading video/logs anywhere.

## Repo structure
- src/main/java/.../vision   : camera + face detection + ROI extraction
- src/main/java/.../signal   : signal processing (preprocess, FFT/DFT, peak picking, quality)
- src/main/java/.../app      : config, main, overlay/logging
- src/test/java/...          : JUnit tests (signal first)

## Build & test commands (must pass)
- ./gradlew test
- ./gradlew run --args="--camera-check"   (manual smoke test; may require camera access)

## Implementation constraints
- Java 25.
- Keep changes small and incremental.
- Prefer small pure functions in `signal` (testable).
- Avoid adding dependencies unless necessary; justify any new dependency in README and in PR description.

## rPPG defaults (tunable via Config)
- Window: 30s (allowed 20–45s)
- HR band: 0.8–2.5 Hz (48–150 bpm) for calm seated adult
- Use green channel mean over skin ROI (mask optional toggle)

## Manual validation
- Provide a simple on-screen overlay: face rect + ROI.
- Log CSV locally: timestamp, avgG, bpm, quality.
- Compare BPM with a pulse oximeter for at least 2–3 minutes.

## Review guidelines (important)
- Do not log or store raw video by default.
- Do not introduce secrets or access local credentials.
- Do not download assets from the internet. If a cascade/model file is needed, document where the user must place it and fail gracefully if missing.
- When editing, update or add JUnit tests when touching `signal` code.