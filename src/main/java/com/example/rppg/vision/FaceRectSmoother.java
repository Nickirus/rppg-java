package com.example.rppg.vision;

public final class FaceRectSmoother {
    private final double alpha;
    private final double maxStepRelative;

    private State state;

    public FaceRectSmoother(double alpha, double maxStepRelative) {
        if (!Double.isFinite(alpha) || alpha <= 0.0 || alpha > 1.0) {
            throw new IllegalArgumentException("alpha must be within (0,1]");
        }
        if (!Double.isFinite(maxStepRelative) || maxStepRelative < 0.0) {
            throw new IllegalArgumentException("maxStepRelative must be >= 0");
        }
        this.alpha = alpha;
        this.maxStepRelative = maxStepRelative;
    }

    public Result update(FaceTracker.Rect rawRect, int frameWidth, int frameHeight) {
        if (rawRect == null) {
            reset();
            return new Result(null, 0.0);
        }

        if (state == null) {
            FaceTracker.Rect bounded = clampToFrame(rawRect, frameWidth, frameHeight);
            state = State.fromRect(bounded);
            return new Result(bounded, 0.0);
        }

        double targetCx = rawRect.x() + rawRect.width() / 2.0;
        double targetCy = rawRect.y() + rawRect.height() / 2.0;
        double targetW = Math.max(1.0, rawRect.width());
        double targetH = Math.max(1.0, rawRect.height());

        double nextCx = state.cx + alpha * (targetCx - state.cx);
        double nextCy = state.cy + alpha * (targetCy - state.cy);
        double nextW = state.width + alpha * (targetW - state.width);
        double nextH = state.height + alpha * (targetH - state.height);

        double centerStepLimit = maxStepRelative * Math.max(1.0, Math.max(state.width, state.height));
        double sizeStepLimitW = maxStepRelative * Math.max(1.0, state.width);
        double sizeStepLimitH = maxStepRelative * Math.max(1.0, state.height);

        nextCx = state.cx + clamp(nextCx - state.cx, -centerStepLimit, centerStepLimit);
        nextCy = state.cy + clamp(nextCy - state.cy, -centerStepLimit, centerStepLimit);
        nextW = state.width + clamp(nextW - state.width, -sizeStepLimitW, sizeStepLimitW);
        nextH = state.height + clamp(nextH - state.height, -sizeStepLimitH, sizeStepLimitH);

        nextW = Math.max(1.0, nextW);
        nextH = Math.max(1.0, nextH);

        int x = (int) Math.round(nextCx - nextW / 2.0);
        int y = (int) Math.round(nextCy - nextH / 2.0);
        int w = Math.max(1, (int) Math.round(nextW));
        int h = Math.max(1, (int) Math.round(nextH));

        FaceTracker.Rect smoothed = clampToFrame(new FaceTracker.Rect(x, y, w, h), frameWidth, frameHeight);
        state = State.fromRect(smoothed);

        double deltaNormalized = normalizedDelta(rawRect, smoothed);
        return new Result(smoothed, deltaNormalized);
    }

    public void reset() {
        state = null;
    }

    public static double normalizedDelta(FaceTracker.Rect rawRect, FaceTracker.Rect smoothedRect) {
        if (rawRect == null || smoothedRect == null) {
            return 0.0;
        }

        double rawCx = rawRect.x() + rawRect.width() / 2.0;
        double rawCy = rawRect.y() + rawRect.height() / 2.0;
        double smoothCx = smoothedRect.x() + smoothedRect.width() / 2.0;
        double smoothCy = smoothedRect.y() + smoothedRect.height() / 2.0;

        double normalizer = Math.max(1.0, Math.max(rawRect.width(), rawRect.height()));
        return Math.hypot(rawCx - smoothCx, rawCy - smoothCy) / normalizer;
    }

    private static FaceTracker.Rect clampToFrame(FaceTracker.Rect rect, int frameWidth, int frameHeight) {
        int x = Math.max(0, rect.x());
        int y = Math.max(0, rect.y());
        int maxW = Math.max(0, frameWidth - x);
        int maxH = Math.max(0, frameHeight - y);
        int w = Math.max(1, Math.min(rect.width(), maxW));
        int h = Math.max(1, Math.min(rect.height(), maxH));
        return new FaceTracker.Rect(x, y, w, h);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record State(double cx, double cy, double width, double height) {
        private static State fromRect(FaceTracker.Rect rect) {
            double cx = rect.x() + rect.width() / 2.0;
            double cy = rect.y() + rect.height() / 2.0;
            return new State(cx, cy, Math.max(1.0, rect.width()), Math.max(1.0, rect.height()));
        }
    }

    public record Result(FaceTracker.Rect smoothedRect, double deltaNormalized) {
    }
}
