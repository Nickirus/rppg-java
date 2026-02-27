package com.example.signal;

public final class BpmStabilizer {
    private final double maxStepPerUpdateBpm;
    private Double lastGoodBpm;
    private Double lastGoodHz;

    public BpmStabilizer(double maxStepPerUpdateBpm) {
        if (!Double.isFinite(maxStepPerUpdateBpm) || maxStepPerUpdateBpm <= 0.0) {
            throw new IllegalArgumentException("maxStepPerUpdateBpm must be > 0");
        }
        this.maxStepPerUpdateBpm = maxStepPerUpdateBpm;
    }

    public Decision update(HeartRateEstimator.Result rawResult, double quality, double qualityThreshold) {
        boolean qualityAccepted = Double.isFinite(quality) && quality >= qualityThreshold;
        boolean rawValid = rawResult != null
                && rawResult.valid()
                && Double.isFinite(rawResult.bpm())
                && rawResult.bpm() > 0.0
                && Double.isFinite(rawResult.hz())
                && rawResult.hz() > 0.0;

        double rawBpm = rawValid ? rawResult.bpm() : Double.NaN;
        double rawHz = rawValid ? rawResult.hz() : Double.NaN;

        if (rawValid && qualityAccepted) {
            double candidateBpm = rawBpm;
            double candidateHz = rawHz;
            BpmStatus status = BpmStatus.VALID;

            if (lastGoodBpm != null) {
                double delta = candidateBpm - lastGoodBpm;
                if (Math.abs(delta) > maxStepPerUpdateBpm) {
                    candidateBpm = lastGoodBpm + Math.copySign(maxStepPerUpdateBpm, delta);
                    candidateHz = candidateBpm / 60.0;
                    status = BpmStatus.HOLDING;
                }
            }

            lastGoodBpm = candidateBpm;
            lastGoodHz = candidateHz;
            return new Decision(status, candidateBpm, rawBpm, rawHz);
        }

        if (lastGoodBpm != null && lastGoodHz != null) {
            return new Decision(BpmStatus.HOLDING, lastGoodBpm, rawBpm, rawHz);
        }

        return Decision.invalid(rawBpm, rawHz);
    }

    public void reset() {
        lastGoodBpm = null;
        lastGoodHz = null;
    }

    public Decision holdCurrent() {
        if (lastGoodBpm != null && lastGoodHz != null) {
            return new Decision(BpmStatus.HOLDING, lastGoodBpm, Double.NaN, Double.NaN);
        }
        return Decision.invalid();
    }

    public record Decision(BpmStatus status, double bpm, double rawBpm, double rawHz) {
        public static Decision invalid(double rawBpm, double rawHz) {
            return new Decision(BpmStatus.INVALID, 0.0, rawBpm, rawHz);
        }

        public static Decision invalid() {
            return invalid(Double.NaN, Double.NaN);
        }
    }
}
