package com.example.signal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoSignalMethodSelectorTest {

    @Test
    void autoFallbackFollowsPosThenChromThenGreen() {
        AutoSignalMethodSelector selector = new AutoSignalMethodSelector(2.0, 10, 0.0);

        SignalMethod m0 = selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.8, 0.2, ns(0.0));
        SignalMethod m1 = selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.8, 0.2, ns(1.0));
        SignalMethod m2 = selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.8, 0.2, ns(2.1));
        SignalMethod m3 = selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.8, 0.2, ns(3.0));
        SignalMethod m4 = selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.8, 0.2, ns(4.3));
        SignalMethod m5 = selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.8, 0.2, ns(5.2));

        assertEquals(SignalMethod.POS, m0);
        assertEquals(SignalMethod.POS, m1);
        assertEquals(SignalMethod.CHROM, m2);
        assertEquals(SignalMethod.CHROM, m3);
        assertEquals(SignalMethod.CHROM, m4);
        assertEquals(SignalMethod.GREEN, m5);
    }

    @Test
    void cooldownPreventsRapidOscillation() {
        AutoSignalMethodSelector selector = new AutoSignalMethodSelector(1.0, 10, 5.0);

        SignalMethod m0 = selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.9, 0.2, ns(0.0));
        SignalMethod m1 = selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.9, 0.2, ns(1.1));
        SignalMethod m2 = selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.9, 0.2, ns(2.2));
        SignalMethod m3 = selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.INVALID, 0.9, 0.2, ns(6.2));

        assertEquals(SignalMethod.POS, m0);
        assertEquals(SignalMethod.CHROM, m1);
        assertEquals(SignalMethod.CHROM, m2);
        assertEquals(SignalMethod.GREEN, m3);
    }

    @Test
    void fixedMethodBypassesAutoFallback() {
        AutoSignalMethodSelector selector = new AutoSignalMethodSelector(0.0, 1, 0.0);

        SignalMethod m0 = selector.onBpmUpdate(SignalMethod.GREEN, BpmStatus.INVALID, 0.0, 0.2, ns(0.0));
        SignalMethod m1 = selector.onBpmUpdate(SignalMethod.GREEN, BpmStatus.INVALID, 0.0, 0.2, ns(10.0));

        assertEquals(SignalMethod.GREEN, m0);
        assertEquals(SignalMethod.GREEN, m1);
    }

    @Test
    void lowQualityFallbackWorksEvenWithValidStatus() {
        AutoSignalMethodSelector selector = new AutoSignalMethodSelector(30.0, 2, 0.0);

        SignalMethod m0 = selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.VALID, 0.05, 0.2, ns(0.0));
        SignalMethod m1 = selector.onBpmUpdate(SignalMethod.AUTO, BpmStatus.VALID, 0.07, 0.2, ns(1.0));

        assertEquals(SignalMethod.POS, m0);
        assertEquals(SignalMethod.CHROM, m1);
    }

    private static long ns(double seconds) {
        return (long) Math.round(seconds * 1_000_000_000.0);
    }
}
