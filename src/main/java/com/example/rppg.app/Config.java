package com.example.rppg.app;

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
        double qualityThreshold
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
                0.20
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
                qualityThreshold
        );
    }
}
