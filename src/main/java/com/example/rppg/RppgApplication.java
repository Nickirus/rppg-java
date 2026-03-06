package com.example.rppg;

import com.example.rppg.app.CameraSmokeCheck;
import com.example.rppg.app.Config;
import com.example.rppg.app.RppgProperties;
import com.example.rppg.app.RtpIngestWorker;
import com.example.rppg.app.RunModeProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
@Slf4j
public class RppgApplication {
    private static final String ARG_WEB = "--web";
    private static final String ARG_RUN = "--run";
    private static final String ARG_CAMERA_CHECK = "--camera-check";
    private static final String ARG_RTP_INGEST = "--rtp-ingest";
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
            case NONE -> log.info(
                    "Usage: --web | --run [--csv=PATH] | --camera-check | "
                            + "--rtp-ingest [--rtp-video-port=5004] [--rtp-audio-port=5002] "
                            + "[--rtp-codec=auto|h264|vp8] [--rtp-width=640] [--rtp-height=480] "
                            + "[--rtp-fps=30] [--rtp-auto-probe-seconds=6] [--rtp-duration-seconds=0]"
            );
        }
    }

    private enum Mode {
        WEB,
        RUN,
        CAMERA_CHECK,
        RTP_INGEST,
        NONE
    }

    private record CliOptions(Mode mode, String csvPath, RtpIngestWorker.RtpIngestConfig rtpIngestConfig) {
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

            for (String arg : args) {
                if (ARG_WEB.equals(arg)) {
                    mode = Mode.WEB;
                } else if (ARG_RUN.equals(arg)) {
                    mode = Mode.RUN;
                } else if (ARG_CAMERA_CHECK.equals(arg)) {
                    mode = Mode.CAMERA_CHECK;
                } else if (ARG_RTP_INGEST.equals(arg)) {
                    mode = Mode.RTP_INGEST;
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
            return new CliOptions(mode, csvPath, ingestConfig);
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
        app.setDefaultProperties(properties);
        try (ConfigurableApplicationContext context = app.run(args)) {
            return context.getBean(RppgProperties.class).toConfig();
        }
    }
}
