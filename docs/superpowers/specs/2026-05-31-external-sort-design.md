# External Sort Demo Design

## Goal

Add a teaching-oriented external sort example to the `algorithm` module. The example should show why external sorting reads data in bounded chunks, sorts each chunk in memory, writes sorted temporary runs, and merges those runs into one sorted output file.

## Scope

- Add `ExternalSortDemo` under `algorithm/src/main/java/yier/bubu/algorithm`.
- Add JUnit 4 tests under `algorithm/src/test/java/yier/bubu/algorithm`.
- Sort text files that contain one integer per line.
- Keep the public API small and testable:

```java
public static void sort(Path input, Path output, Path tempDir, int chunkSize) throws IOException
```

## Behavior

- `chunkSize` is the maximum number of integers loaded into memory at once.
- The sorter reads the input file line by line.
- Each full or final partial chunk is sorted with `Collections.sort`.
- Each sorted chunk is written to a temporary run file in `tempDir`.
- A priority queue performs k-way merge across the temporary run files.
- The output file contains one sorted integer per line.
- Temporary run files created by the sort are deleted in a `finally` block.

## Error Handling

- Reject `chunkSize <= 0` with `IllegalArgumentException`.
- Let malformed integer lines fail with the standard parse exception.
- Let file-system errors propagate as `IOException`.
- Keep output writing simple; if an `IOException` interrupts writing, any partial output is left to the caller to handle.

## Tests

- Sorts an unsorted file.
- Preserves duplicate values and negative numbers.
- Produces an empty output file for an empty input file.
- Rejects a non-positive chunk size.
- Uses small chunk sizes so the tests exercise multiple temporary runs and the merge path.

## Non-Goals

- Support arbitrary record formats.
- Optimize for very large production datasets.
- Add command-line argument parsing or Maven plugins.
