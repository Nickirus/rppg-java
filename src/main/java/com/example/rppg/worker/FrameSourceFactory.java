package com.example.rppg.worker;

public interface FrameSourceFactory {
    DecodedFrameSource create(WorkerSessionConfig config) throws Exception;
}
