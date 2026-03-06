package com.example.rppg.app;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@Slf4j
public final class RtpIngestWorker {
    private static final long LOG_INTERVAL_NS = 2_000_000_000L;
    private static final long DEFAULT_AUTO_PROBE_SECONDS = 6L;

    private RtpIngestWorker() {
    }

    public static boolean run(RtpIngestConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        if (config.codec() == RtpCodec.AUTO) {
            RunOutcome first = runWithCodec(config, RtpCodec.H264, true);
            if (first == RunOutcome.NO_FRAMES) {
                log.warn("AUTO mode fallback: no frames decoded with H264, retrying VP8.");
                RunOutcome second = runWithCodec(config, RtpCodec.VP8, false);
                return second == RunOutcome.SUCCESS;
            }
            return first == RunOutcome.SUCCESS;
        }
        return runWithCodec(config, config.codec(), false) == RunOutcome.SUCCESS;
    }

    static String buildSdp(RtpIngestConfig config, RtpCodec codec) {
        if (codec == RtpCodec.AUTO) {
            throw new IllegalArgumentException("AUTO codec is not valid for SDP generation");
        }
        String codecLine;
        String fmtpLine = "";
        if (codec == RtpCodec.H264) {
            codecLine = "a=rtpmap:96 H264/90000";
            fmtpLine = "a=fmtp:96 packetization-mode=1;profile-level-id=42e01f\r\n";
        } else {
            codecLine = "a=rtpmap:96 VP8/90000";
        }

        StringBuilder sdp = new StringBuilder();
        sdp.append("v=0\r\n")
                .append("o=- 0 0 IN IP4 127.0.0.1\r\n")
                .append("s=RTP ingest\r\n")
                .append("c=IN IP4 0.0.0.0\r\n")
                .append("t=0 0\r\n")
                .append("m=video ").append(config.videoPort()).append(" RTP/AVP 96\r\n")
                .append("a=recvonly\r\n")
                .append("a=framerate:").append(String.format(Locale.US, "%.0f", config.expectedFps())).append("\r\n")
                .append(codecLine).append("\r\n")
                .append(fmtpLine);
        if (config.audioPort() != null) {
            sdp.append("m=audio ")
                    .append(config.audioPort())
                    .append(" RTP/AVP 111\r\n")
                    .append("a=recvonly\r\n")
                    .append("a=rtpmap:111 opus/48000/2\r\n");
        }
        return sdp.toString();
    }

    private static RunOutcome runWithCodec(RtpIngestConfig config, RtpCodec codec, boolean fallbackProbeEnabled) {
        Path sdpPath = null;
        FFmpegFrameGrabber grabber = null;
        try {
            sdpPath = Files.createTempFile("rtp-ingest-", ".sdp");
            Files.writeString(sdpPath, buildSdp(config, codec));

            grabber = new FFmpegFrameGrabber(sdpPath.toAbsolutePath().toString());
            grabber.setFormat("sdp");
            grabber.setOption("protocol_whitelist", "file,udp,rtp");
            grabber.setOption("fflags", "+genpts+discardcorrupt");
            grabber.setOption("flags", "low_delay");
            grabber.setOption("rw_timeout", "5000000");
            grabber.setOption("stimeout", "5000000");
            grabber.setImageWidth(config.expectedWidth());
            grabber.setImageHeight(config.expectedHeight());
            grabber.setFrameRate(config.expectedFps());
            grabber.setAudioChannels(0);

            grabber.start();
            log.info(
                    "RTP ingest started: codec={}, videoPort={}, audioPort={}, expected={}x{}@{}",
                    codec,
                    config.videoPort(),
                    config.audioPort() == null ? "-" : config.audioPort(),
                    config.expectedWidth(),
                    config.expectedHeight(),
                    String.format(Locale.US, "%.1f", config.expectedFps())
            );

            long startedNs = System.nanoTime();
            long lastLogNs = startedNs;
            long probeDeadlineNs = startedNs + config.autoProbeSeconds() * 1_000_000_000L;
            long durationDeadlineNs = config.durationSeconds() <= 0
                    ? Long.MAX_VALUE
                    : startedNs + config.durationSeconds() * 1_000_000_000L;
            long decodedFrames = 0L;

            while (!Thread.currentThread().isInterrupted() && System.nanoTime() < durationDeadlineNs) {
                Frame frame = grabber.grabImage();
                if (frame != null && frame.image != null) {
                    decodedFrames++;
                }

                long nowNs = System.nanoTime();
                if (fallbackProbeEnabled && decodedFrames == 0L && nowNs >= probeDeadlineNs) {
                    log.warn(
                            "No frames decoded within {}s using codec={}.",
                            config.autoProbeSeconds(),
                            codec
                    );
                    return RunOutcome.NO_FRAMES;
                }

                if (nowNs - lastLogNs >= LOG_INTERVAL_NS) {
                    double elapsedSec = Math.max(1e-3, (nowNs - startedNs) / 1_000_000_000.0);
                    double fps = decodedFrames / elapsedSec;
                    log.info(
                            "RTP decode stats: codec={}, frames={}, fps={}, expected={}x{}@{}",
                            codec,
                            decodedFrames,
                            String.format(Locale.US, "%.2f", fps),
                            config.expectedWidth(),
                            config.expectedHeight(),
                            String.format(Locale.US, "%.1f", config.expectedFps())
                    );
                    lastLogNs = nowNs;
                }
            }

            double elapsedSec = Math.max(1e-3, (System.nanoTime() - startedNs) / 1_000_000_000.0);
            double fps = decodedFrames / elapsedSec;
            log.info(
                    "RTP ingest finished: codec={}, frames={}, fps={}",
                    codec,
                    decodedFrames,
                    String.format(Locale.US, "%.2f", fps)
            );
            return decodedFrames > 0 ? RunOutcome.SUCCESS : RunOutcome.NO_FRAMES;
        } catch (Exception e) {
            log.warn("RTP ingest failed with codec={}: {}", codec, e.getMessage());
            return RunOutcome.FAILED;
        } finally {
            closeGrabber(grabber);
            if (sdpPath != null) {
                try {
                    Files.deleteIfExists(sdpPath);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    private static void closeGrabber(FFmpegFrameGrabber grabber) {
        if (grabber == null) {
            return;
        }
        try {
            grabber.stop();
        } catch (Exception ignored) {
            // best-effort cleanup
        }
        try {
            grabber.release();
        } catch (Exception ignored) {
            // best-effort cleanup
        }
        try {
            grabber.close();
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    private enum RunOutcome {
        SUCCESS,
        NO_FRAMES,
        FAILED
    }

    public enum RtpCodec {
        AUTO,
        H264,
        VP8;

        public static RtpCodec fromCli(String raw) {
            if (raw == null || raw.isBlank()) {
                return AUTO;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "auto" -> AUTO;
                case "h264" -> H264;
                case "vp8" -> VP8;
                default -> throw new IllegalArgumentException("Unsupported RTP codec: " + raw);
            };
        }
    }

    public record RtpIngestConfig(
            int videoPort,
            Integer audioPort,
            int expectedWidth,
            int expectedHeight,
            double expectedFps,
            RtpCodec codec,
            long autoProbeSeconds,
            long durationSeconds
    ) {
        public RtpIngestConfig {
            if (videoPort <= 0 || videoPort > 65535) {
                throw new IllegalArgumentException("videoPort must be in [1,65535]");
            }
            if (audioPort != null && (audioPort <= 0 || audioPort > 65535)) {
                throw new IllegalArgumentException("audioPort must be in [1,65535]");
            }
            if (expectedWidth <= 0 || expectedHeight <= 0) {
                throw new IllegalArgumentException("expected width/height must be > 0");
            }
            if (!Double.isFinite(expectedFps) || expectedFps <= 0.0) {
                throw new IllegalArgumentException("expectedFps must be > 0");
            }
            if (codec == null) {
                throw new IllegalArgumentException("codec is required");
            }
            if (autoProbeSeconds <= 0) {
                throw new IllegalArgumentException("autoProbeSeconds must be > 0");
            }
            if (durationSeconds < 0) {
                throw new IllegalArgumentException("durationSeconds must be >= 0");
            }
        }

        public static RtpIngestConfig defaults() {
            return new RtpIngestConfig(
                    5004,
                    null,
                    640,
                    480,
                    30.0,
                    RtpCodec.AUTO,
                    DEFAULT_AUTO_PROBE_SECONDS,
                    0L
            );
        }
    }
}
