package com.example.signal;

public record RoiStats(double meanR, double meanG, double meanB) {
    public double meanBrightness() {
        return (meanR + meanG + meanB) / 3.0;
    }
}
