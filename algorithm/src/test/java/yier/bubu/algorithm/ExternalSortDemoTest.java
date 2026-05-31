package yier.bubu.algorithm;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
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

        ExternalSortDemo.sort(input, output, tempDir, 2);

        Assert.assertEquals(Arrays.asList("1", "2", "3", "5", "7", "9"), readOutput(output));
        Assert.assertEquals(0, countFiles(tempDir));
    }

    @Test
    public void sort_shouldPreserveDuplicateAndNegativeNumbers() throws Exception {
        Path input = writeInput("mixed.txt", Arrays.asList("4", "-1", "4", "0", "-3", "2"));
        Path output = newFile("mixed-sorted.txt");
        Path tempDir = temporaryFolder.newFolder("mixed-runs").toPath();

        ExternalSortDemo.sort(input, output, tempDir, 3);

        Assert.assertEquals(Arrays.asList("-3", "-1", "0", "2", "4", "4"), readOutput(output));
        Assert.assertEquals(0, countFiles(tempDir));
    }

    @Test
    public void sort_shouldCreateEmptyOutputWhenInputIsEmpty() throws Exception {
        Path input = writeInput("empty.txt", Collections.<String>emptyList());
        Path output = newFile("empty-sorted.txt");
        Path tempDir = temporaryFolder.newFolder("empty-runs").toPath();

        ExternalSortDemo.sort(input, output, tempDir, 2);

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
            ExternalSortDemo.sort(input, output, tempDir, 0);
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
}
