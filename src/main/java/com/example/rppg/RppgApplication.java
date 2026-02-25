package com.example.rppg;

import com.example.rppg.app.CameraSmokeCheck;
import com.example.rppg.app.Config;
import com.example.rppg.app.RunModeProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
public class RppgApplication {
    private static final String ARG_WEB = "--web";
    private static final String ARG_RUN = "--run";
    private static final String ARG_CAMERA_CHECK = "--camera-check";
    private static final String ARG_CSV_PREFIX = "--csv=";

    public static void main(String[] args) {
        CliOptions options = CliOptions.parse(args);
        switch (options.mode()) {
            case CAMERA_CHECK -> {
                boolean ok = CameraSmokeCheck.runDefaultCameraCheck();
                if (!ok) {
                    System.exit(1);
                }
            }
            case RUN -> {
                Config config = Config.defaults();
                if (options.csvPath() != null && !options.csvPath().isBlank()) {
                    config = config.withCsvPath(options.csvPath().trim());
                }
                boolean ok = RunModeProcessor.run(config);
                if (!ok) {
                    System.exit(1);
                }
            }
            case WEB -> {
                SpringApplication app = new SpringApplication(RppgApplication.class);
                Map<String, Object> properties = new HashMap<>();
                properties.put("server.address", "127.0.0.1");
                properties.put("server.port", "8080");
                properties.put("spring.main.banner-mode", "off");
                app.setDefaultProperties(properties);
                app.run(args);
            }
            case NONE -> System.out.println("Usage: --web | --run [--csv=PATH] | --camera-check");
        }
    }

    private enum Mode {
        WEB,
        RUN,
        CAMERA_CHECK,
        NONE
    }

    private record CliOptions(Mode mode, String csvPath) {
        private static CliOptions parse(String[] args) {
            Mode mode = Mode.NONE;
            String csvPath = null;
            for (String arg : args) {
                if (ARG_WEB.equals(arg)) {
                    mode = Mode.WEB;
                } else if (ARG_RUN.equals(arg)) {
                    mode = Mode.RUN;
                } else if (ARG_CAMERA_CHECK.equals(arg)) {
                    mode = Mode.CAMERA_CHECK;
                } else if (arg != null && arg.startsWith(ARG_CSV_PREFIX) && arg.length() > ARG_CSV_PREFIX.length()) {
                    csvPath = arg.substring(ARG_CSV_PREFIX.length());
                }
            }
            return new CliOptions(mode, csvPath);
        }
    }
}
