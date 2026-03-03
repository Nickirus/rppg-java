package com.example.rppg.app;

import com.example.rppg.vision.RoiMode;
import com.example.signal.QualityMode;
import com.example.signal.SignalMethod;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rppg")
@Getter
@Setter
public class RppgProperties {
    private Camera camera = new Camera();
    private Window window = new Window();
    private Hr hr = new Hr();
    private Csv csv = new Csv();
    private Signal signal = new Signal();
    private Roi roi = new Roi();
    private Auto auto = new Auto();
    private Warning warning = new Warning();
    private Motion motion = new Motion();

    public Config toConfig() {
        return new Config(
                camera.index,
                camera.width,
                camera.height,
                camera.targetFps,
                camera.previewJpegFps,
                window.seconds,
                hr.minHz,
                hr.maxHz,
                csv.path,
                signal.qualityMode,
                signal.qualityThreshold,
                signal.maxStepPerUpdateBpm,
                signal.temporalNormalization.enabled,
                signal.temporalNormalization.eps,
                signal.method,
                roi.mode,
                roi.foreheadWeight,
                roi.leftCheekWeight,
                roi.rightCheekWeight,
                signal.extractorTemporalWindow,
                auto.fallbackMinHoldSeconds,
                auto.lowQualityUpdatesThreshold,
                auto.switchCooldownSeconds,
                auto.recoveryCooldownSeconds,
                auto.probeWindowSeconds,
                auto.probeValidRatioThreshold,
                auto.probeQualityMargin,
                warning.noFaceSeconds,
                warning.lowLightBrightnessThreshold,
                motion.threshold,
                motion.freezeMinMs,
                motion.resetAfterMs
        );
    }

    @Getter
    @Setter
    public static class Camera {
        private int index = 0;
        private int width = 640;
        private int height = 480;
        private double targetFps = 30.0;
        private double previewJpegFps = 10.0;
    }

    @Getter
    @Setter
    public static class Window {
        private int seconds = 30;
    }

    @Getter
    @Setter
    public static class Hr {
        private double minHz = 0.8;
        private double maxHz = 2.5;
    }

    @Getter
    @Setter
    public static class Csv {
        private String path = "./logs/rppg.csv";
    }

    @Getter
    @Setter
    public static class Signal {
        private QualityMode qualityMode = QualityMode.SNR;
        private SignalMethod method = SignalMethod.AUTO;
        private int extractorTemporalWindow = 32;
        private double qualityThreshold = 0.20;
        private double maxStepPerUpdateBpm = 8.0;
        private TemporalNormalization temporalNormalization = new TemporalNormalization();
    }

    @Getter
    @Setter
    public static class TemporalNormalization {
        private boolean enabled = true;
        private double eps = 1e-6;
    }

    @Getter
    @Setter
    public static class Roi {
        private RoiMode mode = RoiMode.MULTI;
        private double foreheadWeight = 0.30;
        private double leftCheekWeight = 0.35;
        private double rightCheekWeight = 0.35;
    }

    @Getter
    @Setter
    public static class Auto {
        private double fallbackMinHoldSeconds = 8.0;
        private int lowQualityUpdatesThreshold = 3;
        private double switchCooldownSeconds = 8.0;
        private double recoveryCooldownSeconds = 20.0;
        private double probeWindowSeconds = 12.0;
        private double probeValidRatioThreshold = 0.60;
        private double probeQualityMargin = 0.0;
    }

    @Getter
    @Setter
    public static class Warning {
        private double noFaceSeconds = 2.0;
        private double lowLightBrightnessThreshold = 45.0;
    }

    @Getter
    @Setter
    public static class Motion {
        private double threshold = 0.10;
        private long freezeMinMs = 300L;
        private long resetAfterMs = 3000L;
    }
}
