package com.example.rppg.app;

public record Config(
        int cameraIndex,
        int targetWidth,
        int targetHeight,
        double targetFps,
        int windowSeconds,
        double hrMinHz,
        double hrMaxHz
) {
    public static Config defaults() {
        return new Config(
                0,
                640,
                480,
                30.0,
                30,      // 20–45s, возьмём 30s как базу
                0.8,     // 48 bpm
                2.5      // 150 bpm
        );
    }
}
