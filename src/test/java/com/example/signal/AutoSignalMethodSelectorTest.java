package com.example.signal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoSignalMethodSelectorTest {

    @Test
    void afterFallbackToGreen_probeStartsAfterRecoveryCooldown() {
        AutoSignalMethodSelector selector = new AutoSignalMethodSelector(
                0.0,
                1,
                0.0,
                20.0,
                12.0,
                0.6,
                0.05
        );

        selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.0, 0.2, ns(0.0)); // POS -> CHROM
        selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.0, 0.2, ns(1.0)); // CHROM -> GREEN

        AutoSignalMethodSelector.Decision beforeCooldown =
                selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.VALID, 0.4, 0.2, ns(10.0));
        AutoSignalMethodSelector.Decision probeStarted =
                selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.VALID, 0.4, 0.2, ns(22.0));

        assertEquals(SignalMethod.GREEN, beforeCooldown.activeMethod());
        assertEquals(AutoModeState.FALLBACK, beforeCooldown.autoModeState());
        assertEquals(SignalMethod.GREEN, beforeCooldown.effectiveMethod());

        assertEquals(AutoModeState.PROBING, probeStarted.autoModeState());
        assertEquals(SignalMethod.GREEN, probeStarted.activeMethod());
        assertEquals(SignalMethod.CHROM, probeStarted.probeCandidate());
        assertEquals(SignalMethod.CHROM, probeStarted.effectiveMethod());
        assertTrue(probeStarted.processingResetRequired());
    }

    @Test
    void successfulProbeSwitchesUpward() {
        AutoSignalMethodSelector selector = new AutoSignalMethodSelector(
                0.0,
                1,
                0.0,
                2.0,
                4.0,
                0.6,
                0.0
        );

        selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.0, 0.2, ns(0.0)); // POS -> CHROM
        selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.0, 0.2, ns(1.0)); // CHROM -> GREEN
        selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.VALID, 0.4, 0.2, ns(3.1));   // start probe CHROM
        selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.VALID, 0.4, 0.2, ns(4.0));
        selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.VALID, 0.4, 0.2, ns(5.0));
        AutoSignalMethodSelector.Decision completed =
                selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.VALID, 0.4, 0.2, ns(7.2));

        assertEquals(SignalMethod.CHROM, completed.activeMethod());
        assertEquals(SignalMethod.CHROM, completed.effectiveMethod());
        assertEquals(AutoModeState.FALLBACK, completed.autoModeState());
        assertNull(completed.probeCandidate());
        assertFalse(completed.processingResetRequired());
    }

    @Test
    void failedProbeRevertsAndCooldownApplies() {
        AutoSignalMethodSelector selector = new AutoSignalMethodSelector(
                0.0,
                1,
                0.0,
                5.0,
                4.0,
                0.6,
                0.05
        );

        selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.0, 0.2, ns(0.0)); // POS -> CHROM
        selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.0, 0.2, ns(1.0)); // CHROM -> GREEN
        selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.VALID, 0.4, 0.2, ns(6.2));   // start probe CHROM
        selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.05, 0.2, ns(7.0));
        selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.05, 0.2, ns(8.0));
        AutoSignalMethodSelector.Decision failed =
                selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.05, 0.2, ns(10.3));

        AutoSignalMethodSelector.Decision stillCooling =
                selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.VALID, 0.5, 0.2, ns(12.0));
        AutoSignalMethodSelector.Decision probeAgain =
                selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.VALID, 0.5, 0.2, ns(15.4));

        assertEquals(SignalMethod.GREEN, failed.activeMethod());
        assertEquals(AutoModeState.FALLBACK, failed.autoModeState());
        assertNull(failed.probeCandidate());

        assertEquals(AutoModeState.FALLBACK, stillCooling.autoModeState());
        assertEquals(SignalMethod.GREEN, stillCooling.effectiveMethod());
        assertEquals(AutoModeState.PROBING, probeAgain.autoModeState());
        assertEquals(SignalMethod.CHROM, probeAgain.probeCandidate());
    }

    @Test
    void noOscillationOnAlternatingQualitySignals() {
        AutoSignalMethodSelector selector = new AutoSignalMethodSelector(
                0.0,
                1,
                5.0,
                20.0,
                12.0,
                0.6,
                0.05
        );

        AutoSignalMethodSelector.Decision d0 =
                selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.0, 0.2, ns(0.0)); // POS -> CHROM
        AutoSignalMethodSelector.Decision d1 =
                selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.VALID, 0.4, 0.2, ns(1.0));
        AutoSignalMethodSelector.Decision d2 =
                selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.0, 0.2, ns(2.0));
        AutoSignalMethodSelector.Decision d3 =
                selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.VALID, 0.5, 0.2, ns(3.0));
        AutoSignalMethodSelector.Decision d4 =
                selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.0, 0.2, ns(6.1)); // CHROM -> GREEN
        AutoSignalMethodSelector.Decision d5 =
                selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.VALID, 0.6, 0.2, ns(7.0));
        AutoSignalMethodSelector.Decision d6 =
                selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.0, 0.2, ns(8.0));

        assertEquals(SignalMethod.CHROM, d0.activeMethod());
        assertEquals(SignalMethod.CHROM, d1.activeMethod());
        assertEquals(SignalMethod.CHROM, d2.activeMethod());
        assertEquals(SignalMethod.CHROM, d3.activeMethod());
        assertEquals(SignalMethod.GREEN, d4.activeMethod());
        assertEquals(SignalMethod.GREEN, d5.activeMethod());
        assertEquals(SignalMethod.GREEN, d6.activeMethod());
        assertEquals(AutoModeState.FALLBACK, d6.autoModeState());
        assertNull(d6.probeCandidate());
    }

    private static long ns(double seconds) {
        return (long) Math.round(seconds * 1_000_000_000.0);
    }
}
