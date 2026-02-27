package com.example.signal;

public final class AutoSignalMethodSelector {
    private static final long UNSET_NS = Long.MIN_VALUE;

    private final long minHoldNs;
    private final int lowQualityLimitUpdates;
    private final long cooldownNs;

    private SignalMethod activeMethod = SignalMethod.POS;
    private long badSinceNs = UNSET_NS;
    private int consecutiveLowQualityUpdates = 0;
    private long lastSwitchNs = UNSET_NS;

    public AutoSignalMethodSelector(double minHoldSeconds, int lowQualityLimitUpdates, double cooldownSeconds) {
        if (!Double.isFinite(minHoldSeconds) || minHoldSeconds < 0.0) {
            throw new IllegalArgumentException("minHoldSeconds must be >= 0");
        }
        if (lowQualityLimitUpdates < 1) {
            throw new IllegalArgumentException("lowQualityLimitUpdates must be >= 1");
        }
        if (!Double.isFinite(cooldownSeconds) || cooldownSeconds < 0.0) {
            throw new IllegalArgumentException("cooldownSeconds must be >= 0");
        }
        this.minHoldNs = secondsToNs(minHoldSeconds);
        this.lowQualityLimitUpdates = lowQualityLimitUpdates;
        this.cooldownNs = secondsToNs(cooldownSeconds);
    }

    public SignalMethod current(SignalMethod configuredMethod) {
        if (configuredMethod == null || configuredMethod == SignalMethod.AUTO) {
            return activeMethod;
        }
        return configuredMethod;
    }

    public SignalMethod onBpmUpdate(
            SignalMethod configuredMethod,
            BpmStatus bpmStatus,
            double quality,
            double qualityThreshold,
            long nowNs
    ) {
        if (configuredMethod != SignalMethod.AUTO) {
            activeMethod = configuredMethod;
            resetBadState();
            return activeMethod;
        }

        boolean badStatus = bpmStatus == BpmStatus.HOLDING || bpmStatus == BpmStatus.INVALID;
        if (badStatus) {
            if (badSinceNs == UNSET_NS) {
                badSinceNs = nowNs;
            }
        } else {
            badSinceNs = UNSET_NS;
        }

        if (Double.isFinite(quality) && quality < qualityThreshold) {
            consecutiveLowQualityUpdates++;
        } else {
            consecutiveLowQualityUpdates = 0;
        }

        boolean holdExceeded = badSinceNs != UNSET_NS && (nowNs - badSinceNs) >= minHoldNs;
        boolean qualityExceeded = consecutiveLowQualityUpdates >= lowQualityLimitUpdates;
        boolean cooldownElapsed = lastSwitchNs == UNSET_NS || (nowNs - lastSwitchNs) >= cooldownNs;

        if ((holdExceeded || qualityExceeded) && cooldownElapsed) {
            SignalMethod next = fallback(activeMethod);
            if (next != activeMethod) {
                activeMethod = next;
                lastSwitchNs = nowNs;
                resetBadState();
            }
        }

        return activeMethod;
    }

    public void reset() {
        activeMethod = SignalMethod.POS;
        lastSwitchNs = UNSET_NS;
        resetBadState();
    }

    private void resetBadState() {
        badSinceNs = UNSET_NS;
        consecutiveLowQualityUpdates = 0;
    }

    private static SignalMethod fallback(SignalMethod current) {
        if (current == SignalMethod.POS) {
            return SignalMethod.CHROM;
        }
        if (current == SignalMethod.CHROM) {
            return SignalMethod.GREEN;
        }
        return SignalMethod.GREEN;
    }

    private static long secondsToNs(double seconds) {
        return (long) Math.max(0L, Math.round(seconds * 1_000_000_000.0));
    }
}
