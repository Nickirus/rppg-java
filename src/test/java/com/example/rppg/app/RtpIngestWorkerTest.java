package com.example.rppg.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtpIngestWorkerTest {

    @Test
    void buildSdpForH264IncludesExpectedLines() {
        RtpIngestWorker.RtpIngestConfig config = new RtpIngestWorker.RtpIngestConfig(
                5004,
                5002,
                640,
                480,
                30.0,
                RtpIngestWorker.RtpCodec.H264,
                6,
                0
        );

        String sdp = RtpIngestWorker.buildSdp(config, RtpIngestWorker.RtpCodec.H264);

        assertTrue(sdp.contains("m=video 5004 RTP/AVP 96"));
        assertTrue(sdp.contains("a=rtpmap:96 H264/90000"));
        assertTrue(sdp.contains("a=fmtp:96 packetization-mode=1;profile-level-id=42e01f"));
        assertTrue(sdp.contains("m=audio 5002 RTP/AVP 111"));
        assertTrue(sdp.contains("a=rtpmap:111 opus/48000/2"));
    }

    @Test
    void buildSdpForVp8OmitsH264Fmtp() {
        RtpIngestWorker.RtpIngestConfig config = new RtpIngestWorker.RtpIngestConfig(
                5004,
                null,
                640,
                480,
                30.0,
                RtpIngestWorker.RtpCodec.VP8,
                6,
                0
        );

        String sdp = RtpIngestWorker.buildSdp(config, RtpIngestWorker.RtpCodec.VP8);

        assertTrue(sdp.contains("a=rtpmap:96 VP8/90000"));
        assertTrue(!sdp.contains("packetization-mode=1"));
        assertTrue(!sdp.contains("m=audio"));
    }

    @Test
    void codecFromCliParsesKnownValues() {
        assertEquals(RtpIngestWorker.RtpCodec.AUTO, RtpIngestWorker.RtpCodec.fromCli("auto"));
        assertEquals(RtpIngestWorker.RtpCodec.H264, RtpIngestWorker.RtpCodec.fromCli("H264"));
        assertEquals(RtpIngestWorker.RtpCodec.VP8, RtpIngestWorker.RtpCodec.fromCli("vp8"));
        assertThrows(IllegalArgumentException.class, () -> RtpIngestWorker.RtpCodec.fromCli("av1"));
    }
}
