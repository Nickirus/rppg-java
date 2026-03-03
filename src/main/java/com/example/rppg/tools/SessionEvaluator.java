package com.example.rppg.tools;

import com.example.rppg.app.ProcessingStatus;
import com.example.signal.BpmStatus;
import com.example.signal.SignalMethod;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
public final class SessionEvaluator {
    private SessionEvaluator() {
    }

    public static void main(String[] args) throws IOException {
        if (args == null || args.length < 1 || args[0] == null || args[0].isBlank()) {
            throw new IllegalArgumentException("Usage: evaluateSession -Pcsv=<path/to/session.csv>");
        }
        Path csvPath = Path.of(args[0]);
        Metrics metrics = evaluate(csvPath);
        log.info(renderReport(csvPath, metrics));
    }

    public static Metrics evaluate(Path csvPath) throws IOException {
        List<String> lines = Files.readAllLines(csvPath);
        if (lines.isEmpty()) {
            return Metrics.empty();
        }
        List<Row> rows = parseRows(lines);
        if (rows.isEmpty()) {
            return Metrics.empty();
        }

        Instant firstTs = rows.get(0).timestamp();
        Instant lastTs = rows.get(rows.size() - 1).timestamp();
        double durationSec = durationSeconds(firstTs, lastTs);

        int total = rows.size();
        int validCount = 0;
        int posCount = 0;
        int freezeCount = 0;
        double validSum = 0.0;
        double validSumSq = 0.0;
        List<Double> validBpms = new ArrayList<>();
        for (Row row : rows) {
            if (row.activeSignalMethod() == SignalMethod.POS) {
                posCount++;
            }
            if (row.processingStatus() == ProcessingStatus.MOTION_FREEZE) {
                freezeCount++;
            }
            if (row.isValidBpm()) {
                validCount++;
                validSum += row.bpm();
                validSumSq += row.bpm() * row.bpm();
                validBpms.add(row.bpm());
            }
        }

        double validRatio = ratio(validCount, total);
        double posUsageRatio = ratio(posCount, total);
        double freezeRatio = ratio(freezeCount, total);
        double meanBpmValid = validCount > 0 ? validSum / validCount : Double.NaN;
        double stdBpmValid = validCount > 1
                ? Math.sqrt(Math.max(0.0, (validSumSq / validCount) - (meanBpmValid * meanBpmValid)))
                : Double.NaN;
        double jumpRate = jumpRate(validBpms);
        double timeToStable = timeToStable(rows, firstTs);

        return new Metrics(
                durationSec,
                validRatio,
                posUsageRatio,
                freezeRatio,
                meanBpmValid,
                stdBpmValid,
                jumpRate,
                timeToStable
        );
    }

    static String renderReport(Path csvPath, Metrics m) {
        return String.format(
                Locale.US,
                "%nSession Evaluator%nCSV: %s%n%n%-20s %s%n%-20s %s%n%-20s %s%n%-20s %s%n%-20s %s%n%-20s %s%n%-20s %s%n%-20s %s%n",
                csvPath.toAbsolutePath().normalize(),
                "durationSec", fmt(m.durationSec()),
                "validRatio", fmt(m.validRatio()),
                "posUsageRatio", fmt(m.posUsageRatio()),
                "freezeRatio", fmt(m.freezeRatio()),
                "meanBpmValid", fmt(m.meanBpmValid()),
                "stdBpmValid", fmt(m.stdBpmValid()),
                "jumpRate", fmt(m.jumpRate()),
                "timeToStableSec", fmt(m.timeToStableSec())
        );
    }

    private static List<Row> parseRows(List<String> lines) {
        Map<String, Integer> header = parseHeader(lines.get(0));
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] values = line.split(",", -1);
            Instant ts = parseInstant(values, header.get("timestamp"));
            if (ts == null) {
                continue;
            }
            double bpm = parseDouble(values, header.get("bpm"));
            BpmStatus status = parseEnum(values, header.get("bpmStatus"), BpmStatus.class, BpmStatus.INVALID);
            SignalMethod method = parseEnum(values, header.get("activeSignalMethod"), SignalMethod.class, null);
            ProcessingStatus processingStatus = parseEnum(
                    values,
                    header.get("processingStatus"),
                    ProcessingStatus.class,
                    ProcessingStatus.NORMAL
            );
            rows.add(new Row(ts, bpm, status, method, processingStatus));
        }
        return rows;
    }

    private static Map<String, Integer> parseHeader(String line) {
        String[] parts = line.split(",", -1);
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < parts.length; i++) {
            map.put(parts[i].trim(), i);
        }
        return map;
    }

    private static Instant parseInstant(String[] values, Integer idx) {
        if (idx == null || idx < 0 || idx >= values.length) {
            return null;
        }
        String raw = values[idx].trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static double parseDouble(String[] values, Integer idx) {
        if (idx == null || idx < 0 || idx >= values.length) {
            return Double.NaN;
        }
        String raw = values[idx].trim();
        if (raw.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(raw);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static <E extends Enum<E>> E parseEnum(
            String[] values,
            Integer idx,
            Class<E> enumType,
            E defaultValue
    ) {
        if (idx == null || idx < 0 || idx >= values.length) {
            return defaultValue;
        }
        String raw = values[idx].trim();
        if (raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(enumType, raw);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static double jumpRate(List<Double> validBpms) {
        if (validBpms.size() < 2) {
            return 0.0;
        }
        int jumps = 0;
        int transitions = 0;
        double prev = validBpms.get(0);
        for (int i = 1; i < validBpms.size(); i++) {
            double curr = validBpms.get(i);
            transitions++;
            if (Math.abs(curr - prev) > 8.0) {
                jumps++;
            }
            prev = curr;
        }
        return ratio(jumps, transitions);
    }

    private static double timeToStable(List<Row> rows, Instant sessionStart) {
        final double windowSec = 10.0;
        ArrayDeque<Row> window = new ArrayDeque<>();
        int validCount = 0;
        double validSum = 0.0;
        double validSumSq = 0.0;

        for (Row row : rows) {
            window.addLast(row);
            if (row.isValidBpm()) {
                validCount++;
                validSum += row.bpm();
                validSumSq += row.bpm() * row.bpm();
            }

            while (!window.isEmpty()
                    && durationSeconds(window.peekFirst().timestamp(), row.timestamp()) > windowSec) {
                Row removed = window.removeFirst();
                if (removed.isValidBpm()) {
                    validCount--;
                    validSum -= removed.bpm();
                    validSumSq -= removed.bpm() * removed.bpm();
                }
            }

            if (window.isEmpty()) {
                continue;
            }
            double coveredSec = durationSeconds(window.peekFirst().timestamp(), row.timestamp());
            if (coveredSec < windowSec) {
                continue;
            }
            double ratio = ratio(validCount, window.size());
            if (ratio < 0.8) {
                continue;
            }
            if (validCount < 2) {
                continue;
            }
            double mean = validSum / validCount;
            double variance = (validSumSq / validCount) - (mean * mean);
            double std = Math.sqrt(Math.max(0.0, variance));
            if (std <= 3.0) {
                return durationSeconds(sessionStart, row.timestamp());
            }
        }
        return Double.NaN;
    }

    private static double ratio(int num, int den) {
        if (den <= 0) {
            return 0.0;
        }
        return (double) num / (double) den;
    }

    private static double durationSeconds(Instant start, Instant end) {
        return Duration.between(start, end).toNanos() / 1_000_000_000.0;
    }

    private static String fmt(double value) {
        if (!Double.isFinite(value)) {
            return "n/a";
        }
        return String.format(Locale.US, "%.4f", value);
    }

    record Row(
            Instant timestamp,
            double bpm,
            BpmStatus bpmStatus,
            SignalMethod activeSignalMethod,
            ProcessingStatus processingStatus
    ) {
        private boolean isValidBpm() {
            return bpmStatus == BpmStatus.VALID && Double.isFinite(bpm) && bpm > 0.0;
        }
    }

    public record Metrics(
            double durationSec,
            double validRatio,
            double posUsageRatio,
            double freezeRatio,
            double meanBpmValid,
            double stdBpmValid,
            double jumpRate,
            double timeToStableSec
    ) {
        private static Metrics empty() {
            return new Metrics(0.0, 0.0, 0.0, 0.0, Double.NaN, Double.NaN, 0.0, Double.NaN);
        }
    }
}
