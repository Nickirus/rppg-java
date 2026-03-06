package com.example.rppg.worker;

import com.example.rppg.app.RtpIngestWorker;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.nio.file.Files;
import java.nio.file.Path;

final class RtpDecodedFrameSource implements DecodedFrameSource {
    private final WorkerSessionConfig config;
    private final RtpIngestWorker.RtpCodec codec;
    private Path sdpPath;
    private FFmpegFrameGrabber grabber;

    RtpDecodedFrameSource(WorkerSessionConfig config, RtpIngestWorker.RtpCodec codec) {
        this.config = config;
        this.codec = codec;
    }

    @Override
    public void start() throws Exception {
        RtpIngestWorker.RtpIngestConfig ingestConfig = new RtpIngestWorker.RtpIngestConfig(
                config.videoPort(),
                config.audioPort(),
                config.expectedWidth(),
                config.expectedHeight(),
                config.expectedDecodeFps(),
                codec,
                6,
                0
        );
        sdpPath = Files.createTempFile("worker-rtp-", ".sdp");
        Files.writeString(sdpPath, RtpIngestWorker.buildSdp(ingestConfig, codec));

        grabber = new FFmpegFrameGrabber(sdpPath.toAbsolutePath().toString());
        grabber.setFormat("sdp");
        grabber.setOption("protocol_whitelist", "file,udp,rtp");
        grabber.setOption("fflags", "+genpts+discardcorrupt");
        grabber.setOption("flags", "low_delay");
        grabber.setOption("rw_timeout", "5000000");
        grabber.setOption("stimeout", "5000000");
        grabber.setImageWidth(config.expectedWidth());
        grabber.setImageHeight(config.expectedHeight());
        grabber.setFrameRate(config.expectedDecodeFps());
        grabber.setAudioChannels(0);
        grabber.start();
    }

    @Override
    public long grabFrameEpochMs() throws Exception {
        if (grabber == null) {
            return -1L;
        }
        Frame frame = grabber.grabImage();
        if (frame == null || frame.image == null) {
            return -1L;
        }
        return System.currentTimeMillis();
    }

    @Override
    public void close() {
        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception ignored) {
                // best effort
            }
            try {
                grabber.release();
            } catch (Exception ignored) {
                // best effort
            }
            try {
                grabber.close();
            } catch (Exception ignored) {
                // best effort
            }
            grabber = null;
        }
        if (sdpPath != null) {
            try {
                Files.deleteIfExists(sdpPath);
            } catch (Exception ignored) {
                // best effort
            }
            sdpPath = null;
        }
    }
}
