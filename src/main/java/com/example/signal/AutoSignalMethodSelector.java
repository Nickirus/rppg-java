package com.example.signal;

public final class AutoSignalMethodSelector {
    private static final long UNSET_NS = Long.MIN_VALUE;

    private final long minHoldNs;
    private final int lowQualityLimitUpdates;
    private final long switchCooldownNs;
    private final long recoveryCooldownNs;
    private final long probeWindowNs;
    private final double probeValidRatioThreshold;
    private final double probeQualityMargin;

    private SignalMethod activeMethod = SignalMethod.POS;
    private AutoModeState modeState = AutoModeState.STABLE;
    private SignalMethod probeCandidate;
    private long probeEndNs = UNSET_NS;
    private int probeUpdates;
    private int probeValidUpdates;
    private double probeQualitySum;

    private long badSinceNs = UNSET_NS;
    private int consecutiveLowQualityUpdates;
    private long lastSwitchNs = UNSET_NS;
    private long recoveryEligibleNs = 0L;

    public AutoSignalMethodSelector(
            double minHoldSeconds,
            int lowQualityLimitUpdates,
            double switchCooldownSeconds,
            double recoveryCooldownSeconds,
            double probeWindowSeconds,
            double probeValidRatioThreshold,
            double probeQualityMargin
    ) {
        if (!Double.isFinite(minHoldSeconds) || minHoldSeconds < 0.0) {
            throw new IllegalArgumentException("minHoldSeconds must be >= 0");
        }
        if (lowQualityLimitUpdates < 1) {
            throw new IllegalArgumentException("lowQualityLimitUpdates must be >= 1");
        }
        if (!Double.isFinite(switchCooldownSeconds) || switchCooldownSeconds < 0.0) {
            throw new IllegalArgumentException("switchCooldownSeconds must be >= 0");
        }
        if (!Double.isFinite(recoveryCooldownSeconds) || recoveryCooldownSeconds < 0.0) {
            throw new IllegalArgumentException("recoveryCooldownSeconds must be >= 0");
        }
        if (!Double.isFinite(probeWindowSeconds) || probeWindowSeconds <= 0.0) {
            throw new IllegalArgumentException("probeWindowSeconds must be > 0");
        }
        if (!Double.isFinite(probeValidRatioThreshold)
                || probeValidRatioThreshold < 0.0
                || probeValidRatioThreshold > 1.0) {
            throw new IllegalArgumentException("probeValidRatioThreshold must be within [0,1]");
        }
        if (!Double.isFinite(probeQualityMargin)) {
            throw new IllegalArgumentException("probeQualityMargin must be finite");
        }
        this.minHoldNs = secondsToNs(minHoldSeconds);
        this.lowQualityLimitUpdates = lowQualityLimitUpdates;
        this.switchCooldownNs = secondsToNs(switchCooldownSeconds);
        this.recoveryCooldownNs = secondsToNs(recoveryCooldownSeconds);
        this.probeWindowNs = secondsToNs(probeWindowSeconds);
        this.probeValidRatioThreshold = probeValidRatioThreshold;
        this.probeQualityMargin = probeQualityMargin;
    }

    public Decision current(SignalMethod configuredMethod, long nowNs) {
        if (configuredMethod != SignalMethod.AUTO) {
            activeMethod = configuredMethod == null ? SignalMethod.GREEN : configuredMethod;
            modeState = AutoModeState.STABLE;
            clearProbe();
            resetBadState();
            return decision(false, nowNs);
        }
        if (modeState != AutoModeState.PROBING) {
            modeState = activeMethod == SignalMethod.POS ? AutoModeState.STABLE : AutoModeState.FALLBACK;
        }
        return decision(false, nowNs);
    }

    public Decision onBpmUpdate(
            SignalMethod configuredMethod,
            BpmStatus bpmStatus,
            double quality,
            double qualityThreshold,
            long nowNs
    ) {
        if (configuredMethod != SignalMethod.AUTO) {
            return current(configuredMethod, nowNs);
        }

        SignalMethod previousEffective = effectiveMethod();
        if (modeState == AutoModeState.PROBING) {
            probeUpdates++;
            if (bpmStatus == BpmStatus.VALID) {
                probeValidUpdates++;
            }
            if (Double.isFinite(quality)) {
                probeQualitySum += quality;
            }
            if (nowNs >= probeEndNs) {
                boolean probeSucceeded = evaluateProbe(qualityThreshold);
                if (probeSucceeded && probeCandidate != null) {
                    activeMethod = probeCandidate;
                    lastSwitchNs = nowNs;
                }
                clearProbe();
                modeState = activeMethod == SignalMethod.POS ? AutoModeState.STABLE : AutoModeState.FALLBACK;
                recoveryEligibleNs = nowNs + recoveryCooldownNs;
                resetBadState();
            }
        } else {
            updateBadCounters(bpmStatus, quality, qualityThreshold, nowNs);

            boolean holdExceeded = badSinceNs != UNSET_NS && nowNs - badSinceNs >= minHoldNs;
            boolean qualityExceeded = consecutiveLowQualityUpdates >= lowQualityLimitUpdates;
            boolean canSwitchDown = cooldownElapsed(nowNs);
            if ((holdExceeded || qualityExceeded) && canSwitchDown && activeMethod != SignalMethod.GREEN) {
                activeMethod = fallback(activeMethod);
                lastSwitchNs = nowNs;
                recoveryEligibleNs = nowNs + recoveryCooldownNs;
                modeState = AutoModeState.FALLBACK;
                resetBadState();
            } else {
                modeState = activeMethod == SignalMethod.POS ? AutoModeState.STABLE : AutoModeState.FALLBACK;
            }

            if (activeMethod != SignalMethod.POS
                    && bpmStatus == BpmStatus.VALID
                    && Double.isFinite(quality)
                    && quality >= qualityThreshold
                    && nowNs >= recoveryEligibleNs
                    && cooldownElapsed(nowNs)) {
                SignalMethod candidate = recoveryCandidate(activeMethod);
                if (candidate != null && candidate != activeMethod) {
                    startProbe(candidate, nowNs);
                }
            }
        }

        SignalMethod newEffective = effectiveMethod();
        boolean resetRequired = previousEffective != newEffective;
        return decision(resetRequired, nowNs);
    }

    public void reset() {
        activeMethod = SignalMethod.POS;
        modeState = AutoModeState.STABLE;
        clearProbe();
        resetBadState();
        lastSwitchNs = UNSET_NS;
        recoveryEligibleNs = 0L;
    }

    private void updateBadCounters(BpmStatus bpmStatus, double quality, double qualityThreshold, long nowNs) {
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
    }

    private boolean evaluateProbe(double qualityThreshold) {
        if (probeUpdates <= 0) {
            return false;
        }
        double validRatio = (double) probeValidUpdates / (double) probeUpdates;
        double avgQuality = probeQualitySum / (double) probeUpdates;
        return validRatio >= probeValidRatioThreshold && avgQuality >= (qualityThreshold + probeQualityMargin);
    }

    private void startProbe(SignalMethod candidate, long nowNs) {
        modeState = AutoModeState.PROBING;
        probeCandidate = candidate;
        probeEndNs = nowNs + probeWindowNs;
        probeUpdates = 0;
        probeValidUpdates = 0;
        probeQualitySum = 0.0;
        resetBadState();
    }

    private void clearProbe() {
        probeCandidate = null;
        probeEndNs = UNSET_NS;
        probeUpdates = 0;
        probeValidUpdates = 0;
        probeQualitySum = 0.0;
    }

    private void resetBadState() {
        badSinceNs = UNSET_NS;
        consecutiveLowQualityUpdates = 0;
    }

    private boolean cooldownElapsed(long nowNs) {
        return lastSwitchNs == UNSET_NS || nowNs - lastSwitchNs >= switchCooldownNs;
    }

    private SignalMethod effectiveMethod() {
        if (modeState == AutoModeState.PROBING && probeCandidate != null) {
            return probeCandidate;
        }
        return activeMethod;
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

    private static SignalMethod recoveryCandidate(SignalMethod active) {
        if (active == SignalMethod.GREEN) {
            return SignalMethod.CHROM;
        }
        if (active == SignalMethod.CHROM) {
            return SignalMethod.POS;
        }
        return null;
    }

    private Decision decision(boolean resetRequired, long nowNs) {
        double remainingSeconds = 0.0;
        if (modeState == AutoModeState.PROBING && probeEndNs != UNSET_NS) {
            remainingSeconds = Math.max(0.0, (probeEndNs - nowNs) / 1_000_000_000.0);
        }
        return new Decision(
                activeMethod,
                effectiveMethod(),
                modeState,
                probeCandidate,
                remainingSeconds,
                resetRequired
        );
    }

    private static long secondsToNs(double seconds) {
        return (long) Math.max(0L, Math.round(seconds * 1_000_000_000.0));
    }

    public record Decision(
            SignalMethod activeMethod,
            SignalMethod effectiveMethod,
            AutoModeState autoModeState,
            SignalMethod probeCandidate,
            double probeSecondsRemaining,
            boolean processingResetRequired
    ) {
    }
}
