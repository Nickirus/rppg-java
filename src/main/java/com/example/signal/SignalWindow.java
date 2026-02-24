package com.example.signal;

import java.util.ArrayDeque;
import java.util.Deque;

public class SignalWindow {
    private final int capacity;
    private final Deque<Double> samples = new ArrayDeque<>();

    public SignalWindow(int capacity) {
        if (capacity < 10) throw new IllegalArgumentException("capacity too small");
        this.capacity = capacity;
    }

    public void add(double v) {
        if (samples.size() == capacity) samples.removeFirst();
        samples.addLast(v);
    }

    public boolean isFull() {
        return samples.size() == capacity;
    }

    public int size() {
        return samples.size();
    }

    public double[] toArray() {
        double[] arr = new double[samples.size()];
        int i = 0;
        for (double v : samples) arr[i++] = v;
        return arr;
    }
}