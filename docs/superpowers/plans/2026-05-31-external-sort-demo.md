# External Sort Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a teaching-oriented external merge sort example to the `algorithm` module.

**Architecture:** `ExternalSortDemo` exposes one file-based API. It reads bounded chunks of integers, writes sorted temporary run files, and merges those runs with a priority queue. Tests use JUnit 4 temporary folders and small chunk sizes to force the external-sort path.

**Tech Stack:** Java 8, Maven, JUnit 4, `java.nio.file`, `PriorityQueue`.

---

## File Structure

- Create `algorithm/src/main/java/yier/bubu/algorithm/ExternalSortDemo.java`: public demo API plus private helpers for chunk writing, k-way merging, and temporary file cleanup.
- Create `algorithm/src/test/java/yier/bubu/algorithm/ExternalSortDemoTest.java`: JUnit 4 tests for sorted output, duplicates and negative numbers, empty input, and invalid chunk size.

## Task 1: Add Failing Tests

**Files:**
- Create: `algorithm/src/test/java/yier/bubu/algorithm/ExternalSortDemoTest.java`

- [ ] **Step 1: Write the failing test file**

```java
package yier.bubu.algorithm;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public class ExternalSortDemoTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void sort_shouldWriteSortedNumbersUsingMultipleRuns() throws Exception {
        Path input = writeInput("numbers.txt", Arrays.asList("9", "1", "5", "3", "7", "2"));
        Path output = newFile("sorted.txt");
        Path tempDir = temporaryFolder.newFolder("runs").toPath();

        invokeSort(input, output, tempDir, 2);

        Assert.assertEquals(Arrays.asList("1", "2", "3", "5", "7", "9"), readOutput(output));
        Assert.assertEquals(0, countFiles(tempDir));
    }

    @Test
    public void sort_shouldPreserveDuplicateAndNegativeNumbers() throws Exception {
        Path input = writeInput("mixed.txt", Arrays.asList("4", "-1", "4", "0", "-3", "2"));
        Path output = newFile("mixed-sorted.txt");
        Path tempDir = temporaryFolder.newFolder("mixed-runs").toPath();

        invokeSort(input, output, tempDir, 3);

        Assert.assertEquals(Arrays.asList("-3", "-1", "0", "2", "4", "4"), readOutput(output));
        Assert.assertEquals(0, countFiles(tempDir));
    }

    @Test
    public void sort_shouldCreateEmptyOutputWhenInputIsEmpty() throws Exception {
        Path input = writeInput("empty.txt", Collections.<String>emptyList());
        Path output = newFile("empty-sorted.txt");
        Path tempDir = temporaryFolder.newFolder("empty-runs").toPath();

        invokeSort(input, output, tempDir, 2);

        Assert.assertTrue(Files.exists(output));
        Assert.assertTrue(readOutput(output).isEmpty());
        Assert.assertEquals(0, countFiles(tempDir));
    }

    @Test
    public void sort_shouldRejectNonPositiveChunkSize() throws Exception {
        Path input = writeInput("invalid.txt", Arrays.asList("1"));
        Path output = newFile("invalid-sorted.txt");
        Path tempDir = temporaryFolder.newFolder("invalid-runs").toPath();

        try {
            invokeSort(input, output, tempDir, 0);
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            Assert.assertEquals("chunkSize must be positive", expected.getMessage());
        }
    }

    private Path writeInput(String fileName, List<String> lines) throws IOException {
        Path path = newFile(fileName);
        Files.write(path, lines, StandardCharsets.UTF_8);
        return path;
    }

    private Path newFile(String fileName) throws IOException {
        File file = temporaryFolder.newFile(fileName);
        return file.toPath();
    }

    private List<String> readOutput(Path output) throws IOException {
        return Files.readAllLines(output, StandardCharsets.UTF_8);
    }

    private long countFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.count();
        }
    }

    private void invokeSort(Path input, Path output, Path tempDir, int chunkSize) throws Exception {
        Class<?> demoClass;
        try {
            demoClass = Class.forName("yier.bubu.algorithm.ExternalSortDemo");
        } catch (ClassNotFoundException e) {
            throw new AssertionError("ExternalSortDemo class should exist", e);
        }

        Method sort = demoClass.getMethod("sort", Path.class, Path.class, Path.class, int.class);
        try {
            sort.invoke(null, input, output, tempDir, chunkSize);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `mvn -pl algorithm -Dtest=ExternalSortDemoTest test`

Expected: test failure containing `ExternalSortDemo class should exist`.

## Task 2: Implement ExternalSortDemo

**Files:**
- Create: `algorithm/src/main/java/yier/bubu/algorithm/ExternalSortDemo.java`

- [ ] **Step 1: Write the implementation**

```java
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
            for (RunReader reader : readers) {
                reader.close();
            }
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
```

- [ ] **Step 2: Run the focused test and verify GREEN**

Run: `mvn -pl algorithm -Dtest=ExternalSortDemoTest test`

Expected: all four `ExternalSortDemoTest` tests pass.

## Task 3: Verify Module

**Files:**
- No source changes.

- [ ] **Step 1: Run all algorithm module tests**

Run: `mvn -pl algorithm test`

Expected: all algorithm tests pass.

- [ ] **Step 2: Inspect workspace changes**

Run: `git status --short`

Expected: new source and test files plus this plan file; unrelated `jdk/docs/array-basics.md` remains untouched.
