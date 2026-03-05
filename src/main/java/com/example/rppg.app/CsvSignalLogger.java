package com.example.rppg.app;

import com.example.signal.AutoModeState;
import com.example.signal.BpmStatus;
import com.example.signal.SignalMethod;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;

final class CsvSignalLogger implements Closeable {
    static final String HEADER = "timestamp,avgG,bpm,quality,rawBpm,bpmStatus,activeSignalMethod,autoModeState,motionScore,smoothedRectDelta,skinCoverage,processingStatus,windowFill,fps,peakHz";

    private final BufferedWriter writer;

    private CsvSignalLogger(BufferedWriter writer) {
        this.writer = writer;
    }

    static CsvSignalLogger open(String csvPath) throws IOException {
        Path outputPath = Paths.get(csvPath).toAbsolutePath().normalize();
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        boolean fileExists = Files.exists(outputPath);
        BufferedWriter writer = Files.newBufferedWriter(
                outputPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
        CsvSignalLogger logger = new CsvSignalLogger(writer);
        if (!fileExists || Files.size(outputPath) == 0L) {
            logger.writer.write(HEADER);
            logger.writer.newLine();
            logger.writer.flush();
        }
        return logger;
    }

    void log(
            Instant timestamp,
            double avgG,
            Double bpm,
            double quality,
            double rawBpm,
            BpmStatus bpmStatus,
            SignalMethod activeSignalMethod,
            AutoModeState autoModeState,
            double motionScore,
            double smoothedRectDelta,
            double skinCoverage,
            ProcessingStatus processingStatus,
            double windowFill,
            double fps,
            double peakHz
    ) throws IOException {
        String bpmField = (bpm == null || !Double.isFinite(bpm)) ? "" : String.format(Locale.US, "%.3f", bpm);
        String rawBpmField = formatOptionalNumber(rawBpm);
        String peakHzField = formatOptionalNumber(peakHz);
        String line = String.format(
                Locale.US,
                "%s,%.6f,%s,%.6f,%s,%s,%s,%s,%.6f,%.6f,%.6f,%s,%.6f,%.6f,%s",
                timestamp.toString(),
                avgG,
                bpmField,
                quality,
                rawBpmField,
                bpmStatus == null ? "" : bpmStatus.name(),
                activeSignalMethod == null ? "" : activeSignalMethod.name(),
                autoModeState == null ? "" : autoModeState.name(),
                motionScore,
                smoothedRectDelta,
                skinCoverage,
                processingStatus == null ? "" : processingStatus.name(),
                windowFill,
                fps,
                peakHzField
        );
        writer.write(line);
        writer.newLine();
        writer.flush();
    }

    private static String formatOptionalNumber(double value) {
        if (!Double.isFinite(value)) {
            return "";
        }
        return String.format(Locale.US, "%.6f", value);
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
