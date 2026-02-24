package com.example.rppg.vision;

public interface FaceTracker {
    Rect detectFace(Frame frame);

    record Frame(int width, int height) {
    }

    record Rect(int x, int y, int width, int height) {
    }
}