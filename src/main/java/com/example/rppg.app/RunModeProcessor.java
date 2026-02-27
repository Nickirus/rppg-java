package com.example.rppg.app;

import lombok.extern.slf4j.Slf4j;

import java.util.Locale;

@Slf4j
public final class RunModeProcessor {
    private static final long POLL_INTERVAL_MS = 250L;
    private static final long MAX_RUN_DURATION_MS = 120_000L;
    private static final long POST_FULL_MONITOR_MS = 6_000L;
    private static final long DEBUG_LOG_INTERVAL_MS = 2_000L;

    private RunModeProcessor() {
    }

    public static boolean run(Config config) {
        log.info("Run mode selected. csvPath={}", config.csvPath());
        RppgEngine engine = new RppgEngine(config);
        engine.start();

        long startedMs = System.currentTimeMillis();
        long fullSinceMs = -1L;
        String lastPrinted = "";
        long lastDebugLogMs = Long.MIN_VALUE;
        try {
            while (System.currentTimeMillis() - startedMs <= MAX_RUN_DURATION_MS) {
                RppgSnapshot snapshot = engine.getLatestSnapshot();

                boolean hasError = snapshot.warnings().stream().anyMatch(code -> code.startsWith("ERROR_"));
                if (hasError) {
                    log.warn("Run mode failed: {}", String.join(",", snapshot.warnings()));
                    return false;
                }

                String line = String.format(
                        Locale.US,
                        "avgG=%.2f, windowFill=%.1f%%, bpm=%.1f, quality=%.3f, warnings=%s",
                        snapshot.avgG(),
                        snapshot.windowFill(),
                        snapshot.bpm(),
                        snapshot.quality(),
                        snapshot.warnings().isEmpty() ? "none" : String.join(",", snapshot.warnings())
                );
                long nowMs = System.currentTimeMillis();
                if (!line.equals(lastPrinted) && (lastDebugLogMs == Long.MIN_VALUE || nowMs - lastDebugLogMs >= DEBUG_LOG_INTERVAL_MS)) {
                    log.debug(line);
                    lastPrinted = line;
                    lastDebugLogMs = nowMs;
                }

                if (snapshot.windowFill() >= 100.0) {
                    if (fullSinceMs < 0L) {
                        fullSinceMs = System.currentTimeMillis();
                    }
                    if (System.currentTimeMillis() - fullSinceMs >= POST_FULL_MONITOR_MS) {
                        log.info("Run mode completed after post-full updates.");
                        return true;
                    }
                }

                Thread.sleep(POLL_INTERVAL_MS);
            }

            log.warn("Run mode timed out before completion.");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Run mode interrupted.");
            return false;
        } finally {
            engine.stop();
        }
    }
}
