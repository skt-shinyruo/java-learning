package yier.bubu.algorithm;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public class ExternalSortDemo {

    public static void sort(Path input, Path output, Path tempDir, int chunkSize) throws IOException {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }

        Files.createDirectories(tempDir);
        List<Path> runs = new ArrayList<Path>();

        try {
            runs = splitAndSortRuns(input, tempDir, chunkSize);
            mergeRuns(runs, output);
        } finally {
            deleteRuns(runs);
        }
    }

    private static List<Path> splitAndSortRuns(Path input, Path tempDir, int chunkSize) throws IOException {
        List<Path> runs = new ArrayList<Path>();
        List<Integer> chunk = new ArrayList<Integer>(chunkSize);

        try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                chunk.add(Integer.parseInt(line.trim()));
                if (chunk.size() == chunkSize) {
                    runs.add(writeRun(chunk, tempDir));
                    chunk.clear();
                }
            }
        }

        if (!chunk.isEmpty()) {
            runs.add(writeRun(chunk, tempDir));
        }

        return runs;
    }

    private static Path writeRun(List<Integer> values, Path tempDir) throws IOException {
        Collections.sort(values);
        Path run = Files.createTempFile(tempDir, "external-sort-run-", ".txt");

        try (BufferedWriter writer = Files.newBufferedWriter(run, StandardCharsets.UTF_8)) {
            for (Integer value : values) {
                writer.write(String.valueOf(value));
                writer.newLine();
            }
        }

        return run;
    }

    private static void mergeRuns(List<Path> runs, Path output) throws IOException {
        List<RunReader> readers = new ArrayList<RunReader>();
        PriorityQueue<RunEntry> heap = new PriorityQueue<RunEntry>(new Comparator<RunEntry>() {
            public int compare(RunEntry left, RunEntry right) {
                return Integer.compare(left.value, right.value);
            }
        });

        try {
            for (Path run : runs) {
                RunReader reader = new RunReader(run);
                readers.add(reader);
                Integer value = reader.readNext();
                if (value != null) {
                    heap.add(new RunEntry(value, reader));
                }
            }

            try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                while (!heap.isEmpty()) {
                    RunEntry entry = heap.poll();
                    writer.write(String.valueOf(entry.value));
                    writer.newLine();

                    Integer next = entry.reader.readNext();
                    if (next != null) {
                        heap.add(new RunEntry(next, entry.reader));
                    }
                }
            }
        } finally {
            closeReaders(readers);
        }
    }

    private static void closeReaders(List<RunReader> readers) throws IOException {
        IOException failure = null;

        for (RunReader reader : readers) {
            try {
                reader.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    private static void deleteRuns(List<Path> runs) throws IOException {
        IOException failure = null;

        for (Path run : runs) {
            try {
                Files.deleteIfExists(run);
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    private static class RunEntry {
        private final int value;
        private final RunReader reader;

        private RunEntry(int value, RunReader reader) {
            this.value = value;
            this.reader = reader;
        }
    }

    private static class RunReader {
        private final BufferedReader reader;

        private RunReader(Path run) throws IOException {
            this.reader = Files.newBufferedReader(run, StandardCharsets.UTF_8);
        }

        private Integer readNext() throws IOException {
            String line = reader.readLine();
            if (line == null) {
                return null;
            }
            return Integer.parseInt(line.trim());
        }

        private void close() throws IOException {
            reader.close();
        }
    }
}
