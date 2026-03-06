package com.example.rppg.worker;

import com.example.rppg.app.RtpIngestWorker;

public final class RtpFrameSourceFactory implements FrameSourceFactory {
    @Override
    public DecodedFrameSource create(WorkerSessionConfig config) {
        RtpIngestWorker.RtpCodec codec = config.codec() == RtpIngestWorker.RtpCodec.AUTO
                ? RtpIngestWorker.RtpCodec.H264
                : config.codec();
        return new RtpDecodedFrameSource(config, codec);
    }
}
