package com.example.rppg.app;

import java.util.Locale;

final class RunModeProcessor {
    private static final long POLL_INTERVAL_MS = 250L;
    private static final long MAX_RUN_DURATION_MS = 120_000L;
    private static final long POST_FULL_MONITOR_MS = 6_000L;

    private RunModeProcessor() {
    }

    static boolean run(Config config) {
        RppgEngine engine = new RppgEngine(config);
        engine.start();

        long startedMs = System.currentTimeMillis();
        long fullSinceMs = -1L;
        String lastPrinted = "";
        try {
            while (System.currentTimeMillis() - startedMs <= MAX_RUN_DURATION_MS) {
                RppgSnapshot snapshot = engine.getLatestSnapshot();

                boolean hasError = snapshot.warnings().stream().anyMatch(code -> code.startsWith("ERROR_"));
                if (hasError) {
                    System.err.println("Run mode failed: " + String.join(",", snapshot.warnings()));
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
                if (!line.equals(lastPrinted)) {
                    System.out.println(line);
                    lastPrinted = line;
                }

                if (snapshot.windowFill() >= 100.0) {
                    if (fullSinceMs < 0L) {
                        fullSinceMs = System.currentTimeMillis();
                    }
                    if (System.currentTimeMillis() - fullSinceMs >= POST_FULL_MONITOR_MS) {
                        System.out.println("Run mode completed after post-full updates.");
                        return true;
                    }
                }

                Thread.sleep(POLL_INTERVAL_MS);
            }

            System.err.println("Run mode timed out before completion.");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Run mode interrupted.");
            return false;
        } finally {
            engine.stop();
        }
    }
}
