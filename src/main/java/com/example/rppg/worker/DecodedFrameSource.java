package com.example.rppg.worker;

public interface DecodedFrameSource extends AutoCloseable {
    void start() throws Exception;

    long grabFrameEpochMs() throws Exception;

    @Override
    void close() throws Exception;
}
