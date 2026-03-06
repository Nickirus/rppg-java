package com.example.rppg;

import com.example.rppg.app.CameraSmokeCheck;
import com.example.rppg.app.Config;
import com.example.rppg.app.RppgProperties;
import com.example.rppg.app.RtpIngestWorker;
import com.example.rppg.app.RunModeProcessor;
import com.example.rppg.worker.MultiSessionWorkerRuntime;
import com.example.rppg.worker.WorkerSessionConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SpringBootApplication
@Slf4j
public class RppgApplication {
    private static final String ARG_WEB = "--web";
    private static final String ARG_RUN = "--run";
    private static final String ARG_CAMERA_CHECK = "--camera-check";
    private static final String ARG_RTP_INGEST = "--rtp-ingest";
    private static final String ARG_WORKER = "--worker";
    private static final String ARG_CSV_PREFIX = "--csv=";
    private static final String ARG_RTP_PORT_PREFIX = "--rtp-port=";
    private static final String ARG_RTP_VIDEO_PORT_PREFIX = "--rtp-video-port=";
    private static final String ARG_RTP_AUDIO_PORT_PREFIX = "--rtp-audio-port=";
    private static final String ARG_RTP_CODEC_PREFIX = "--rtp-codec=";
    private static final String ARG_RTP_WIDTH_PREFIX = "--rtp-width=";
    private static final String ARG_RTP_HEIGHT_PREFIX = "--rtp-height=";
    private static final String ARG_RTP_FPS_PREFIX = "--rtp-fps=";
    private static final String ARG_RTP_AUTO_PROBE_SECONDS_PREFIX = "--rtp-auto-probe-seconds=";
    private static final String ARG_RTP_DURATION_SECONDS_PREFIX = "--rtp-duration-seconds=";
    private static final String ARG_WORKER_API_HOST_PREFIX = "--worker-api-host=";
    private static final String ARG_WORKER_API_PORT_PREFIX = "--worker-api-port=";
    private static final String ARG_WORKER_SESSION_PREFIX = "--worker-session=";
    private static final String ARG_WORKER_WIDTH_PREFIX = "--worker-width=";
    private static final String ARG_WORKER_HEIGHT_PREFIX = "--worker-height=";
    private static final String ARG_WORKER_FPS_PREFIX = "--worker-fps=";
    private static final String ARG_WORKER_CODEC_PREFIX = "--worker-codec=";
    private static final String ARG_WORKER_QUEUE_CAPACITY_PREFIX = "--worker-queue-capacity=";
    private static final String ARG_WORKER_TARGET_FPS_PREFIX = "--worker-target-fps=";
    private static final String ARG_WORKER_EMIT_INTERVAL_PREFIX = "--worker-emit-interval-ms=";
    private static final String ARG_WORKER_STATUS_LOG_INTERVAL_PREFIX = "--worker-status-log-interval-ms=";

    public static void main(String[] args) {
        log.info("Application start requested.");
        CliOptions options = CliOptions.parse(args);
        switch (options.mode()) {
            case CAMERA_CHECK -> {
                log.info("Selected mode: camera-check");
                boolean ok = CameraSmokeCheck.runDefaultCameraCheck();
                if (!ok) {
                    System.exit(1);
                }
            }
            case RUN -> {
                log.info("Selected mode: run");
                Config config = loadConfigFromApplicationYaml(args);
                if (options.csvPath() != null && !options.csvPath().isBlank()) {
                    config = config.withCsvPath(options.csvPath().trim());
                }
                boolean ok = RunModeProcessor.run(config);
                if (!ok) {
                    System.exit(1);
                }
            }
            case WEB -> {
                log.info("Selected mode: web");
                SpringApplication app = new SpringApplication(RppgApplication.class);
                Map<String, Object> properties = new HashMap<>();
                properties.put("server.address", "127.0.0.1");
                properties.put("server.port", "8080");
                properties.put("spring.main.banner-mode", "off");
                app.setDefaultProperties(properties);
                log.info("Starting web server on http://127.0.0.1:8080");
                app.run(args);
            }
            case RTP_INGEST -> {
                log.info("Selected mode: rtp-ingest");
                boolean ok = RtpIngestWorker.run(options.rtpIngestConfig());
                if (!ok) {
                    System.exit(1);
                }
            }
            case WORKER -> {
                log.info("Selected mode: worker");
                log.info(
                        "Connecting worker to ingest API at {}:{} with {} configured session(s).",
                        options.workerApiHost(),
                        options.workerApiPort(),
                        options.workerSessions().size()
                );
                try (MultiSessionWorkerRuntime runtime = new MultiSessionWorkerRuntime(
                        options.workerApiHost(),
                        options.workerApiPort())
                ) {
                    Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "worker-shutdown"));
                    boolean allStarted = runtime.startSessions(options.workerSessions());
                    int active = runtime.activeSessionCount();
                    if (active <= 0) {
                        log.warn("Worker did not start any sessions.");
                        System.exit(1);
                    }
                    if (!allStarted) {
                        log.warn("Worker started partially: activeSessions={}", active);
                    } else {
                        log.info("Worker started: activeSessions={}", active);
                    }
                    runtime.runUntilInterrupted(options.workerStatusLogIntervalMs());
                }
            }
            case NONE -> log.info(
                    "Usage: --web | --run [--csv=PATH] | --camera-check | "
                            + "--rtp-ingest [--rtp-video-port=5004] [--rtp-audio-port=5002] "
                            + "[--rtp-codec=auto|h264|vp8] [--rtp-width=640] [--rtp-height=480] "
                            + "[--rtp-fps=30] [--rtp-auto-probe-seconds=6] [--rtp-duration-seconds=0] | "
                            + "--worker [--worker-api-host=127.0.0.1] [--worker-api-port=9090] "
                            + "[--worker-session=1:5004] [--worker-session=2:5006] "
                            + "[--worker-codec=auto|h264|vp8] [--worker-width=640] [--worker-height=480] "
                            + "[--worker-fps=30] [--worker-target-fps=10] "
                            + "[--worker-queue-capacity=120] [--worker-emit-interval-ms=1000]"
            );
        }
    }

    private enum Mode {
        WEB,
        RUN,
        CAMERA_CHECK,
        RTP_INGEST,
        WORKER,
        NONE
    }

    private record CliOptions(
            Mode mode,
            String csvPath,
            RtpIngestWorker.RtpIngestConfig rtpIngestConfig,
            String workerApiHost,
            int workerApiPort,
            long workerStatusLogIntervalMs,
            List<WorkerSessionConfig> workerSessions
    ) {
        private static CliOptions parse(String[] args) {
            Mode mode = Mode.NONE;
            String csvPath = null;
            RtpIngestWorker.RtpIngestConfig defaults = RtpIngestWorker.RtpIngestConfig.defaults();
            int rtpVideoPort = defaults.videoPort();
            Integer rtpAudioPort = defaults.audioPort();
            int rtpWidth = defaults.expectedWidth();
            int rtpHeight = defaults.expectedHeight();
            double rtpFps = defaults.expectedFps();
            long rtpAutoProbeSeconds = defaults.autoProbeSeconds();
            long rtpDurationSeconds = defaults.durationSeconds();
            RtpIngestWorker.RtpCodec rtpCodec = defaults.codec();
            String workerApiHost = "127.0.0.1";
            int workerApiPort = 9090;
            int workerWidth = 640;
            int workerHeight = 480;
            double workerDecodeFps = 30.0;
            RtpIngestWorker.RtpCodec workerCodec = RtpIngestWorker.RtpCodec.AUTO;
            int workerQueueCapacity = 120;
            double workerTargetFps = 10.0;
            long workerEmitIntervalMs = 1000L;
            long workerStatusLogIntervalMs = 2000L;
            List<String> workerSessionSpecs = new ArrayList<>();

            for (String arg : args) {
                if (ARG_WEB.equals(arg)) {
                    mode = Mode.WEB;
                } else if (ARG_RUN.equals(arg)) {
                    mode = Mode.RUN;
                } else if (ARG_CAMERA_CHECK.equals(arg)) {
                    mode = Mode.CAMERA_CHECK;
                } else if (ARG_RTP_INGEST.equals(arg)) {
                    mode = Mode.RTP_INGEST;
                } else if (ARG_WORKER.equals(arg)) {
                    mode = Mode.WORKER;
                } else if (arg != null && arg.startsWith(ARG_CSV_PREFIX) && arg.length() > ARG_CSV_PREFIX.length()) {
                    csvPath = arg.substring(ARG_CSV_PREFIX.length());
                } else if (arg != null && arg.startsWith(ARG_RTP_PORT_PREFIX) && arg.length() > ARG_RTP_PORT_PREFIX.length()) {
                    rtpVideoPort = parseIntArg(arg, ARG_RTP_PORT_PREFIX, rtpVideoPort);
                } else if (arg != null && arg.startsWith(ARG_RTP_VIDEO_PORT_PREFIX) && arg.length() > ARG_RTP_VIDEO_PORT_PREFIX.length()) {
                    rtpVideoPort = parseIntArg(arg, ARG_RTP_VIDEO_PORT_PREFIX, rtpVideoPort);
                } else if (arg != null && arg.startsWith(ARG_RTP_AUDIO_PORT_PREFIX)) {
                    String raw = arg.substring(ARG_RTP_AUDIO_PORT_PREFIX.length()).trim();
                    rtpAudioPort = raw.isEmpty() ? null : parseIntArg(arg, ARG_RTP_AUDIO_PORT_PREFIX, defaults.audioPort() == null ? 5002 : defaults.audioPort());
                } else if (arg != null && arg.startsWith(ARG_RTP_CODEC_PREFIX) && arg.length() > ARG_RTP_CODEC_PREFIX.length()) {
                    rtpCodec = RtpIngestWorker.RtpCodec.fromCli(arg.substring(ARG_RTP_CODEC_PREFIX.length()));
                } else if (arg != null && arg.startsWith(ARG_RTP_WIDTH_PREFIX) && arg.length() > ARG_RTP_WIDTH_PREFIX.length()) {
                    rtpWidth = parseIntArg(arg, ARG_RTP_WIDTH_PREFIX, rtpWidth);
                } else if (arg != null && arg.startsWith(ARG_RTP_HEIGHT_PREFIX) && arg.length() > ARG_RTP_HEIGHT_PREFIX.length()) {
                    rtpHeight = parseIntArg(arg, ARG_RTP_HEIGHT_PREFIX, rtpHeight);
                } else if (arg != null && arg.startsWith(ARG_RTP_FPS_PREFIX) && arg.length() > ARG_RTP_FPS_PREFIX.length()) {
                    rtpFps = parseDoubleArg(arg, ARG_RTP_FPS_PREFIX, rtpFps);
                } else if (arg != null && arg.startsWith(ARG_RTP_AUTO_PROBE_SECONDS_PREFIX) && arg.length() > ARG_RTP_AUTO_PROBE_SECONDS_PREFIX.length()) {
                    rtpAutoProbeSeconds = parseLongArg(arg, ARG_RTP_AUTO_PROBE_SECONDS_PREFIX, rtpAutoProbeSeconds);
                } else if (arg != null && arg.startsWith(ARG_RTP_DURATION_SECONDS_PREFIX) && arg.length() > ARG_RTP_DURATION_SECONDS_PREFIX.length()) {
                    rtpDurationSeconds = parseLongArg(arg, ARG_RTP_DURATION_SECONDS_PREFIX, rtpDurationSeconds);
                } else if (arg != null && arg.startsWith(ARG_WORKER_API_HOST_PREFIX) && arg.length() > ARG_WORKER_API_HOST_PREFIX.length()) {
                    String parsedHost = arg.substring(ARG_WORKER_API_HOST_PREFIX.length()).trim();
                    if (!parsedHost.isEmpty()) {
                        workerApiHost = parsedHost;
                    }
                } else if (arg != null && arg.startsWith(ARG_WORKER_API_PORT_PREFIX) && arg.length() > ARG_WORKER_API_PORT_PREFIX.length()) {
                    workerApiPort = parseIntArg(arg, ARG_WORKER_API_PORT_PREFIX, workerApiPort);
                } else if (arg != null && arg.startsWith(ARG_WORKER_SESSION_PREFIX) && arg.length() > ARG_WORKER_SESSION_PREFIX.length()) {
                    workerSessionSpecs.add(arg.substring(ARG_WORKER_SESSION_PREFIX.length()).trim());
                } else if (arg != null && arg.startsWith(ARG_WORKER_WIDTH_PREFIX) && arg.length() > ARG_WORKER_WIDTH_PREFIX.length()) {
                    workerWidth = parseIntArg(arg, ARG_WORKER_WIDTH_PREFIX, workerWidth);
                } else if (arg != null && arg.startsWith(ARG_WORKER_HEIGHT_PREFIX) && arg.length() > ARG_WORKER_HEIGHT_PREFIX.length()) {
                    workerHeight = parseIntArg(arg, ARG_WORKER_HEIGHT_PREFIX, workerHeight);
                } else if (arg != null && arg.startsWith(ARG_WORKER_FPS_PREFIX) && arg.length() > ARG_WORKER_FPS_PREFIX.length()) {
                    workerDecodeFps = parseDoubleArg(arg, ARG_WORKER_FPS_PREFIX, workerDecodeFps);
                } else if (arg != null && arg.startsWith(ARG_WORKER_CODEC_PREFIX) && arg.length() > ARG_WORKER_CODEC_PREFIX.length()) {
                    try {
                        workerCodec = RtpIngestWorker.RtpCodec.fromCli(arg.substring(ARG_WORKER_CODEC_PREFIX.length()));
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid worker codec argument '{}', using {}", arg, workerCodec);
                    }
                } else if (arg != null && arg.startsWith(ARG_WORKER_QUEUE_CAPACITY_PREFIX) && arg.length() > ARG_WORKER_QUEUE_CAPACITY_PREFIX.length()) {
                    workerQueueCapacity = parseIntArg(arg, ARG_WORKER_QUEUE_CAPACITY_PREFIX, workerQueueCapacity);
                } else if (arg != null && arg.startsWith(ARG_WORKER_TARGET_FPS_PREFIX) && arg.length() > ARG_WORKER_TARGET_FPS_PREFIX.length()) {
                    workerTargetFps = parseDoubleArg(arg, ARG_WORKER_TARGET_FPS_PREFIX, workerTargetFps);
                } else if (arg != null && arg.startsWith(ARG_WORKER_EMIT_INTERVAL_PREFIX) && arg.length() > ARG_WORKER_EMIT_INTERVAL_PREFIX.length()) {
                    workerEmitIntervalMs = parseLongArg(arg, ARG_WORKER_EMIT_INTERVAL_PREFIX, workerEmitIntervalMs);
                } else if (arg != null && arg.startsWith(ARG_WORKER_STATUS_LOG_INTERVAL_PREFIX) && arg.length() > ARG_WORKER_STATUS_LOG_INTERVAL_PREFIX.length()) {
                    workerStatusLogIntervalMs = parseLongArg(arg, ARG_WORKER_STATUS_LOG_INTERVAL_PREFIX, workerStatusLogIntervalMs);
                }
            }

            RtpIngestWorker.RtpIngestConfig ingestConfig = new RtpIngestWorker.RtpIngestConfig(
                    rtpVideoPort,
                    rtpAudioPort,
                    rtpWidth,
                    rtpHeight,
                    rtpFps,
                    rtpCodec,
                    rtpAutoProbeSeconds,
                    rtpDurationSeconds
            );
            List<WorkerSessionConfig> workerSessions = buildWorkerSessions(
                    workerSessionSpecs,
                    workerWidth,
                    workerHeight,
                    workerDecodeFps,
                    workerCodec,
                    workerQueueCapacity,
                    workerTargetFps,
                    workerEmitIntervalMs
            );
            return new CliOptions(
                    mode,
                    csvPath,
                    ingestConfig,
                    workerApiHost,
                    workerApiPort,
                    workerStatusLogIntervalMs,
                    workerSessions
            );
        }

        private static int parseIntArg(String arg, String prefix, int fallback) {
            String raw = arg.substring(prefix.length());
            try {
                return Integer.parseInt(raw.trim());
            } catch (Exception e) {
                log.warn("Invalid integer argument '{}', using {}", arg, fallback);
                return fallback;
            }
        }

        private static long parseLongArg(String arg, String prefix, long fallback) {
            String raw = arg.substring(prefix.length());
            try {
                return Long.parseLong(raw.trim());
            } catch (Exception e) {
                log.warn("Invalid long argument '{}', using {}", arg, fallback);
                return fallback;
            }
        }

        private static double parseDoubleArg(String arg, String prefix, double fallback) {
            String raw = arg.substring(prefix.length());
            try {
                return Double.parseDouble(raw.trim());
            } catch (Exception e) {
                log.warn("Invalid double argument '{}', using {}", arg, fallback);
                return fallback;
            }
        }

        private static List<WorkerSessionConfig> buildWorkerSessions(
                List<String> specs,
                int width,
                int height,
                double decodeFps,
                RtpIngestWorker.RtpCodec codec,
                int queueCapacity,
                double targetFps,
                long emitIntervalMs
        ) {
            if (specs.isEmpty()) {
                return List.of(new WorkerSessionConfig(
                        1L,
                        5004,
                        null,
                        width,
                        height,
                        decodeFps,
                        codec,
                        queueCapacity,
                        targetFps,
                        emitIntervalMs,
                        "FRAME_TIMELINE"
                ));
            }

            List<WorkerSessionConfig> configs = new ArrayList<>();
            for (String spec : specs) {
                WorkerSessionConfig cfg = parseWorkerSessionSpec(
                        spec,
                        width,
                        height,
                        decodeFps,
                        codec,
                        queueCapacity,
                        targetFps,
                        emitIntervalMs
                );
                if (cfg != null) {
                    configs.add(cfg);
                }
            }
            return configs;
        }

        private static WorkerSessionConfig parseWorkerSessionSpec(
                String spec,
                int width,
                int height,
                double decodeFps,
                RtpIngestWorker.RtpCodec codec,
                int queueCapacity,
                double targetFps,
                long emitIntervalMs
        ) {
            String raw = spec == null ? "" : spec.trim();
            String[] parts = raw.split(":");
            if (parts.length < 2 || parts.length > 3) {
                log.warn("Invalid worker session spec '{}', expected sessionId:videoPort[:audioPort]", raw);
                return null;
            }
            try {
                long sessionId = Long.parseLong(parts[0].trim());
                int videoPort = Integer.parseInt(parts[1].trim());
                Integer audioPort = null;
                if (parts.length == 3 && !parts[2].trim().isEmpty()) {
                    audioPort = Integer.parseInt(parts[2].trim());
                }
                return new WorkerSessionConfig(
                        sessionId,
                        videoPort,
                        audioPort,
                        width,
                        height,
                        decodeFps,
                        codec,
                        queueCapacity,
                        targetFps,
                        emitIntervalMs,
                        "FRAME_TIMELINE"
                );
            } catch (Exception e) {
                log.warn("Invalid worker session spec '{}': {}", raw, e.getMessage());
                return null;
            }
        }
    }

    private static Config loadConfigFromApplicationYaml(String[] args) {
        SpringApplication app = new SpringApplication(RppgApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.main.banner-mode", "off");
        properties.put(
                "spring.autoconfigure.exclude",
                DataSourceAutoConfiguration.class.getName() + ","
                        + HibernateJpaAutoConfiguration.class.getName() + ","
                        + FlywayAutoConfiguration.class.getName()
        );
        properties.put("rppg.grpc.enabled", "false");
        app.setDefaultProperties(properties);
        try (ConfigurableApplicationContext context = app.run(args)) {
            return context.getBean(RppgProperties.class).toConfig();
        }
    }
}
