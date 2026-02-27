package com.example.rppg.app;

import com.example.rppg.vision.FaceTracker;

public final class MotionGate {
    private static final long UNSET_NS = Long.MIN_VALUE;

    private final double motionThreshold;
    private final long motionFreezeMinNs;
    private final long motionResetAfterNs;

    private FaceTracker.Rect previousFace;
    private long highMotionSinceNs = UNSET_NS;
    private long freezeSinceNs = UNSET_NS;
    private boolean frozen;
    private boolean resetIssuedInFreeze;

    public MotionGate(double motionThreshold, long motionFreezeMinMs, long motionResetAfterMs) {
        if (!Double.isFinite(motionThreshold) || motionThreshold < 0.0) {
            throw new IllegalArgumentException("motionThreshold must be >= 0");
        }
        if (motionFreezeMinMs < 0L || motionResetAfterMs < 0L) {
            throw new IllegalArgumentException("motion timings must be >= 0");
        }
        this.motionThreshold = motionThreshold;
        this.motionFreezeMinNs = motionFreezeMinMs * 1_000_000L;
        this.motionResetAfterNs = motionResetAfterMs * 1_000_000L;
    }

    public State update(FaceTracker.Rect currentFace, long nowNs) {
        if (currentFace == null) {
            previousFace = null;
            highMotionSinceNs = UNSET_NS;
            frozen = false;
            freezeSinceNs = UNSET_NS;
            resetIssuedInFreeze = false;
            return new State(0.0, false, false);
        }

        double motionScore = 0.0;
        if (previousFace != null) {
            motionScore = motionScore(previousFace, currentFace);
        }
        previousFace = currentFace;

        if (motionScore > motionThreshold) {
            if (highMotionSinceNs == UNSET_NS) {
                highMotionSinceNs = nowNs;
            }
            if (!frozen && nowNs - highMotionSinceNs >= motionFreezeMinNs) {
                frozen = true;
                freezeSinceNs = nowNs;
                resetIssuedInFreeze = false;
            }
        } else {
            highMotionSinceNs = UNSET_NS;
            frozen = false;
            freezeSinceNs = UNSET_NS;
            resetIssuedInFreeze = false;
        }

        boolean shouldResetWindow = false;
        if (frozen && !resetIssuedInFreeze && freezeSinceNs != UNSET_NS && nowNs - freezeSinceNs >= motionResetAfterNs) {
            shouldResetWindow = true;
            resetIssuedInFreeze = true;
        }

        return new State(motionScore, frozen, shouldResetWindow);
    }

    public void reset() {
        previousFace = null;
        highMotionSinceNs = UNSET_NS;
        freezeSinceNs = UNSET_NS;
        frozen = false;
        resetIssuedInFreeze = false;
    }

    public static double motionScore(FaceTracker.Rect previousFace, FaceTracker.Rect currentFace) {
        if (previousFace == null || currentFace == null) {
            return 0.0;
        }

        double prevCx = previousFace.x() + previousFace.width() / 2.0;
        double prevCy = previousFace.y() + previousFace.height() / 2.0;
        double currCx = currentFace.x() + currentFace.width() / 2.0;
        double currCy = currentFace.y() + currentFace.height() / 2.0;

        double dx = currCx - prevCx;
        double dy = currCy - prevCy;
        double normalizer = Math.max(1.0, Math.max(previousFace.width(), previousFace.height()));
        double centerShiftNorm = Math.hypot(dx, dy) / normalizer;

        double prevArea = Math.max(1.0, (double) previousFace.width() * previousFace.height());
        double currArea = Math.max(1.0, (double) currentFace.width() * currentFace.height());
        double areaChangeNorm = Math.abs(currArea - prevArea) / prevArea;

        return Math.max(centerShiftNorm, areaChangeNorm);
    }

    public record State(double motionScore, boolean frozen, boolean shouldResetWindow) {
    }
}
