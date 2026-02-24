package com.example.rppg.app;

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
            logger.writer.write("timestamp,avgG,bpm,quality");
            logger.writer.newLine();
            logger.writer.flush();
        }
        return logger;
    }

    void log(Instant timestamp, double avgG, Double bpm, double quality) throws IOException {
        String bpmField = (bpm == null || !Double.isFinite(bpm)) ? "" : String.format(Locale.US, "%.3f", bpm);
        String line = String.format(
                Locale.US,
                "%s,%.6f,%s,%.6f",
                timestamp.toString(),
                avgG,
                bpmField,
                quality
        );
        writer.write(line);
        writer.newLine();
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
