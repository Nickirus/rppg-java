package com.example.rppg.app;

import com.example.signal.AutoModeState;
import com.example.signal.BpmStatus;
import com.example.signal.SignalMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvSignalLoggerTest {

    @TempDir
    Path tempDir;

    @Test
    void headerContainsRequiredColumns() throws Exception {
        Path csv = tempDir.resolve("session.csv");

        try (CsvSignalLogger logger = CsvSignalLogger.open(csv.toString())) {
            logger.log(
                    Instant.parse("2026-03-03T00:00:00Z"),
                    120.5,
                    72.0,
                    0.45,
                    71.8,
                    BpmStatus.VALID,
                    SignalMethod.POS,
                    AutoModeState.STABLE,
                    0.03,
                    0.01,
                    0.72,
                    ProcessingStatus.NORMAL,
                    100.0,
                    29.8,
                    1.20
            );
        }

        List<String> lines = Files.readAllLines(csv);
        String header = lines.get(0);
        assertTrue(header.contains("timestamp"));
        assertTrue(header.contains("bpm"));
        assertTrue(header.contains("rawBpm"));
        assertTrue(header.contains("bpmStatus"));
        assertTrue(header.contains("quality"));
        assertTrue(header.contains("activeSignalMethod"));
        assertTrue(header.contains("autoModeState"));
        assertTrue(header.contains("motionScore"));
        assertTrue(header.contains("smoothedRectDelta"));
        assertTrue(header.contains("skinCoverage"));
        assertTrue(header.contains("processingStatus"));
        assertTrue(header.contains("windowFill"));
        assertTrue(header.contains("fps"));
        assertTrue(header.contains("peakHz"));
    }
}
