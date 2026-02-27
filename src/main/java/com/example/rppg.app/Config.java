package com.example.rppg.app;

import com.example.signal.SignalMethod;

public record Config(
        int cameraIndex,
        int targetWidth,
        int targetHeight,
        double targetFps,
        double previewJpegFps,
        int windowSeconds,
        double hrMinHz,
        double hrMaxHz,
        String csvPath,
        double qualityThreshold,
        double maxStepPerUpdateBpm,
        SignalMethod signalMethod,
        int extractorTemporalWindow,
        double autoFallbackMinHoldSeconds,
        int autoLowQualityUpdatesThreshold,
        double autoSwitchCooldownSeconds,
        double autoRecoveryCooldownSeconds,
        double autoProbeWindowSeconds,
        double autoProbeValidRatioThreshold,
        double autoProbeQualityMargin,
        double noFaceWarningSeconds,
        double lowLightBrightnessThreshold,
        double motionCenterThreshold,
        double motionAreaChangeThreshold
) {
    public static Config defaults() {
        return new Config(
                0,
                640,
                480,
                30.0,
                10.0,
                30,
                0.8,
                2.5,
                "./logs/rppg.csv",
                0.20,
                8.0,
                SignalMethod.AUTO,
                32,
                8.0,
                3,
                8.0,
                20.0,
                12.0,
                0.60,
                0.05,
                2.0,
                45.0,
                0.08,
                0.25
        );
    }

    public Config withCsvPath(String value) {
        return new Config(
                cameraIndex,
                targetWidth,
                targetHeight,
                targetFps,
                previewJpegFps,
                windowSeconds,
                hrMinHz,
                hrMaxHz,
                value,
                qualityThreshold,
                maxStepPerUpdateBpm,
                signalMethod,
                extractorTemporalWindow,
                autoFallbackMinHoldSeconds,
                autoLowQualityUpdatesThreshold,
                autoSwitchCooldownSeconds,
                autoRecoveryCooldownSeconds,
                autoProbeWindowSeconds,
                autoProbeValidRatioThreshold,
                autoProbeQualityMargin,
                noFaceWarningSeconds,
                lowLightBrightnessThreshold,
                motionCenterThreshold,
                motionAreaChangeThreshold
        );
    }
}
