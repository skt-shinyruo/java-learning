# JVM Lab Batch 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first usable JVM failure lab batch for `JL-02 内存模型与 GC` and `JL-05 线上故障排查`.

**Architecture:** Keep the first batch inside the existing `jvm` Maven module and existing `yier.bubu.jvm` package to avoid a package migration before the lab is usable. Add a new `JvmLabApp` entry point that delegates to existing memory demos and new manual-only failure demos; keep dangerous OOM, deadlock, CPU, and blocking behavior out of normal tests.

**Tech Stack:** Java 8, Maven, JUnit 4, MkDocs, existing JVM tools (`jcmd`, `jstack`, `jmap`, `jstat`, `javap` only where docs mention it).

---

## Source Specs

- Parent spec: `docs/superpowers/specs/jvm-lab/jvm-lab-master-spec.md`
- Child spec: `docs/superpowers/specs/jvm-lab/jl-02-memory-gc-spec.md`
- Child spec: `docs/superpowers/specs/jvm-lab/jl-05-troubleshooting-spec.md`

## Scope

This plan implements the first batch only:

- `JL-02-LAB-01` Heap OOM
- `JL-02-LAB-02` Direct Memory OOM documentation around the existing demo
- `JL-02-LAB-03` Metaspace OOM documentation around the existing demo
- `JL-02-LAB-04` StackOverflow documentation around the existing demo
- `JL-02-LAB-05` GC pressure and GC log analysis
- `JL-05-LAB-01` High CPU
- `JL-05-LAB-02` Static collection memory leak
- `JL-05-LAB-04` Deadlock
- `JL-05-LAB-05` Thread blocking
- `JL-05-LAB-06` Frequent GC
- `JL-05-LAB-07` Direct Memory OOM troubleshooting path

`JL-05-LAB-03` ThreadLocal leak is intentionally deferred to Batch 2 because the first batch already covers heap retention, thread stacks, blocking, deadlock, direct memory, and GC pressure. Add a note in the troubleshooting lab doc that ThreadLocal leak is tracked but not included in Batch 1.

## File Structure

Create:

- `jvm/src/main/java/yier/bubu/jvm/JvmLabApp.java`: first-batch CLI entry point.
- `jvm/src/main/java/yier/bubu/jvm/HeapOomDemo.java`: heap OOM lab.
- `jvm/src/main/java/yier/bubu/jvm/GcPressureDemo.java`: allocation pressure and GC log lab.
- `jvm/src/main/java/yier/bubu/jvm/HighCpuDemo.java`: named busy-loop threads.
- `jvm/src/main/java/yier/bubu/jvm/StaticMemoryLeakDemo.java`: static list heap retention lab.
- `jvm/src/main/java/yier/bubu/jvm/DeadlockDemo.java`: named deadlock threads.
- `jvm/src/main/java/yier/bubu/jvm/ThreadBlockDemo.java`: named lock holder and blocked waiters.
- `jvm/src/test/java/yier/bubu/jvm/JvmLabAppTest.java`: safe CLI help test.
- `jvm/src/test/java/yier/bubu/jvm/HeapOomDemoTest.java`: config parsing test only.
- `jvm/src/test/java/yier/bubu/jvm/GcPressureDemoTest.java`: config parsing test only.
- `jvm/src/test/java/yier/bubu/jvm/TroubleshootingDemoConfigTest.java`: config parsing test only.
- `jvm/docs/labs/jl-02-memory-gc-lab.md`: memory and GC lab overview.
- `jvm/docs/labs/jl-05-troubleshooting-lab.md`: troubleshooting lab overview.
- `jvm/docs/runbooks/jl-02-heap-oom-runbook.md`
- `jvm/docs/runbooks/jl-02-direct-memory-oom-runbook.md`
- `jvm/docs/runbooks/jl-02-metaspace-oom-runbook.md`
- `jvm/docs/runbooks/jl-05-high-cpu-runbook.md`
- `jvm/docs/runbooks/jl-05-memory-leak-runbook.md`
- `jvm/docs/runbooks/jl-05-full-gc-runbook.md`
- `jvm/docs/runbooks/jl-05-thread-block-runbook.md`
- `jvm/docs/reports/jl-02-heap-oom-report-template.md`
- `jvm/docs/reports/jl-05-troubleshooting-report-template.md`

Modify:

- `jvm/docs/jvm-memory.md`: link to the new lab docs.
- `mkdocs/docs/jvm/index.md`: list new lab pages.
- `mkdocs/mkdocs.yml`: add new JVM Lab nav entries.

Do not modify:

- `references/`
- `mkdocs/site/`
- root `pom.xml`
- `jvm/pom.xml`, unless implementation reveals a compile-time need. The planned code uses only JDK classes and JUnit 4 tests.

## Task 1: Add Safe CLI Entry Point

**Files:**
- Create: `jvm/src/main/java/yier/bubu/jvm/JvmLabApp.java`
- Test: `jvm/src/test/java/yier/bubu/jvm/JvmLabAppTest.java`

- [ ] **Step 1: Write the failing help test**

Create `jvm/src/test/java/yier/bubu/jvm/JvmLabAppTest.java`:

```java
package yier.bubu.jvm;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

public class JvmLabAppTest {

    @Test
    public void help_shouldListFirstBatchCommands() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(out, true, "UTF-8"));

            JvmLabApp.main(new String[]{"help"});
        } finally {
            System.setOut(original);
        }

        String text = out.toString("UTF-8");
        Assert.assertTrue(text.contains("heap-oom"));
        Assert.assertTrue(text.contains("gc-pressure"));
        Assert.assertTrue(text.contains("high-cpu"));
        Assert.assertTrue(text.contains("static-leak"));
        Assert.assertTrue(text.contains("deadlock"));
        Assert.assertTrue(text.contains("thread-block"));
        Assert.assertTrue(text.contains("Manual failure labs"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
mvn -pl jvm -am -Dtest=JvmLabAppTest test
```

Expected: FAIL because `JvmLabApp` does not exist.

- [ ] **Step 3: Create `JvmLabApp` with first-batch dispatch**

Create `jvm/src/main/java/yier/bubu/jvm/JvmLabApp.java`:

```java
package yier.bubu.jvm;

import java.util.Arrays;

public final class JvmLabApp {
    private JvmLabApp() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printHelp();
            return;
        }

        String command = args[0].toLowerCase();
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        switch (command) {
            case "help":
            case "-h":
            case "--help":
                printHelp();
                return;
            case "inspect":
                MemoryInspector.printAll();
                return;
            case "heap-oom":
                HeapOomDemo.run(rest);
                return;
            case "direct-oom":
                DirectMemoryDemo.run(rest);
                return;
            case "metaspace-oom":
                MetaspaceDemo.run(rest);
                return;
            case "stack-overflow":
                StackOverflowDemo.run(rest);
                return;
            case "gc-pressure":
                GcPressureDemo.run(rest);
                return;
            case "high-cpu":
                HighCpuDemo.run(rest);
                return;
            case "static-leak":
                StaticMemoryLeakDemo.run(rest);
                return;
            case "deadlock":
                DeadlockDemo.run(rest);
                return;
            case "thread-block":
                ThreadBlockDemo.run(rest);
                return;
            default:
                System.out.println("Unknown command: " + command);
                printHelp();
        }
    }

    private static void printHelp() {
        System.out.println("Usage:");
        System.out.println("  java -cp jvm/target/classes yier.bubu.jvm.JvmLabApp <command> [options]");
        System.out.println();
        System.out.println("Manual failure labs. Run in a controlled terminal; several commands intentionally consume CPU, memory, or locks.");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  inspect                         Print JVM/memory/thread/classloading summary");
        System.out.println("  heap-oom       [--mb N]          Allocate heap byte arrays and hold references");
        System.out.println("  direct-oom     [--mb N]          Allocate DirectByteBuffer and hold references");
        System.out.println("  metaspace-oom  [--count N]       Define generated classes and hold Class references");
        System.out.println("  stack-overflow                   Trigger StackOverflowError to observe -Xss effect");
        System.out.println("  gc-pressure    [--seconds N]     Allocate short-lived and retained objects");
        System.out.println("  high-cpu       [--threads N]     Start named busy-loop threads");
        System.out.println("  static-leak    [--mb N]          Grow a static list to simulate heap retention");
        System.out.println("  deadlock                         Create a two-thread monitor deadlock");
        System.out.println("  thread-block   [--waiters N]     Create BLOCKED threads on one monitor");
        System.out.println("  help                            Show this help");
        System.out.println();
        System.out.println("Build:");
        System.out.println("  mvn -pl jvm -am -DskipTests package");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -Xms64m -Xmx64m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=target/jvm-lab-heap.hprof -cp jvm/target/classes yier.bubu.jvm.JvmLabApp heap-oom --mb 128 --chunkMb 1");
        System.out.println("  java -XX:MaxDirectMemorySize=64m -cp jvm/target/classes yier.bubu.jvm.JvmLabApp direct-oom --mb 96 --chunkMb 4 --touch true --reportEvery 4");
        System.out.println("  java -XX:MaxMetaspaceSize=64m -cp jvm/target/classes yier.bubu.jvm.JvmLabApp metaspace-oom --count 20000 --reportEvery 1000");
        System.out.println("  java -Xms128m -Xmx128m -cp jvm/target/classes yier.bubu.jvm.JvmLabApp gc-pressure --seconds 60 --chunkKb 256 --retainEvery 8 --maxRetained 256");
        System.out.println("  java -cp jvm/target/classes yier.bubu.jvm.JvmLabApp high-cpu --threads 1 --seconds 120");
        System.out.println("  java -cp jvm/target/classes yier.bubu.jvm.JvmLabApp deadlock --sleepSeconds 600");
    }
}
```

- [ ] **Step 4: Run the help test**

Run:

```bash
mvn -pl jvm -am -Dtest=JvmLabAppTest test
```

Expected: PASS for `JvmLabAppTest`.

- [ ] **Step 5: Commit**

```bash
git add jvm/src/main/java/yier/bubu/jvm/JvmLabApp.java jvm/src/test/java/yier/bubu/jvm/JvmLabAppTest.java
git commit -m "feat: add JVM lab CLI entrypoint"
```

## Task 2: Add Heap OOM Lab

**Files:**
- Create: `jvm/src/main/java/yier/bubu/jvm/HeapOomDemo.java`
- Test: `jvm/src/test/java/yier/bubu/jvm/HeapOomDemoTest.java`

- [ ] **Step 1: Write config tests**

Create `jvm/src/test/java/yier/bubu/jvm/HeapOomDemoTest.java`:

```java
package yier.bubu.jvm;

import org.junit.Assert;
import org.junit.Test;

public class HeapOomDemoTest {

    @Test
    public void configFrom_shouldParseExplicitValues() {
        HeapOomDemo.Config config = HeapOomDemo.Config.from(new String[]{
                "--mb", "128",
                "--chunkMb", "2",
                "--reportEvery", "7",
                "--sleepSeconds", "3"
        });

        Assert.assertEquals(128, config.totalMb);
        Assert.assertEquals(2, config.chunkMb);
        Assert.assertEquals(7, config.reportEvery);
        Assert.assertEquals(3, config.sleepSeconds);
    }

    @Test
    public void configFrom_shouldKeepSafeDefaults() {
        HeapOomDemo.Config config = HeapOomDemo.Config.from(new String[0]);

        Assert.assertEquals(96, config.totalMb);
        Assert.assertEquals(1, config.chunkMb);
        Assert.assertEquals(16, config.reportEvery);
        Assert.assertEquals(0, config.sleepSeconds);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -pl jvm -am -Dtest=HeapOomDemoTest test
```

Expected: FAIL because `HeapOomDemo` does not exist.

- [ ] **Step 3: Implement HeapOomDemo**

Create `jvm/src/main/java/yier/bubu/jvm/HeapOomDemo.java`:

```java
package yier.bubu.jvm;

import java.util.ArrayList;
import java.util.List;

final class HeapOomDemo {
    private HeapOomDemo() {
    }

    static void run(String[] args) throws Exception {
        Config config = Config.from(args);
        if (config.totalMb <= 0 || config.chunkMb <= 0) {
            System.out.println("Invalid args: --mb and --chunkMb must be > 0");
            return;
        }

        int chunks = Math.max(1, config.totalMb / config.chunkMb);
        int chunkBytes = config.chunkMb * 1024 * 1024;

        System.out.println("[HeapOomDemo]");
        System.out.println("Allocating heap byte arrays and holding references.");
        System.out.println("totalMb=" + config.totalMb + " chunkMb=" + config.chunkMb + " chunks=" + chunks);
        System.out.println("Tip: run with -Xms64m -Xmx64m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=target/jvm-lab-heap.hprof");
        System.out.println();

        List<byte[]> retained = new ArrayList<byte[]>(chunks);
        for (int i = 0; i < chunks; i++) {
            byte[] block = new byte[chunkBytes];
            block[0] = (byte) i;
            retained.add(block);

            if ((i + 1) % config.reportEvery == 0 || i == chunks - 1) {
                System.out.println("allocatedChunks=" + (i + 1)
                        + " approxAllocated=" + MemoryInspector.formatBytes((long) (i + 1) * chunkBytes));
                MemoryInspector.printMemorySummary();
                System.out.println();
            }
        }

        System.out.println("Holding references to prevent GC from freeing heap arrays. retained.size=" + retained.size());
        if (config.sleepSeconds > 0) {
            System.out.println("Sleeping " + config.sleepSeconds + "s (attach tools like jcmd/jmap if you want).");
            Thread.sleep(config.sleepSeconds * 1000L);
        }
    }

    static final class Config {
        final int totalMb;
        final int chunkMb;
        final int reportEvery;
        final int sleepSeconds;

        private Config(int totalMb, int chunkMb, int reportEvery, int sleepSeconds) {
            this.totalMb = totalMb;
            this.chunkMb = chunkMb;
            this.reportEvery = reportEvery;
            this.sleepSeconds = sleepSeconds;
        }

        static Config from(String[] args) {
            return new Config(
                    CliArgs.getInt(args, "--mb", 96),
                    CliArgs.getInt(args, "--chunkMb", 1),
                    Math.max(1, CliArgs.getInt(args, "--reportEvery", 16)),
                    CliArgs.getInt(args, "--sleepSeconds", 0)
            );
        }
    }
}
```

- [ ] **Step 4: Run Heap OOM config tests**

Run:

```bash
mvn -pl jvm -am -Dtest=HeapOomDemoTest test
```

Expected: PASS.

- [ ] **Step 5: Manually smoke test bounded allocation**

Run:

```bash
mvn -pl jvm -am -DskipTests package
java -Xms128m -Xmx128m -cp jvm/target/classes yier.bubu.jvm.JvmLabApp heap-oom --mb 8 --chunkMb 1 --reportEvery 4
```

Expected: exits normally after allocating 8 MiB and prints `[HeapOomDemo]`.

- [ ] **Step 6: Commit**

```bash
git add jvm/src/main/java/yier/bubu/jvm/HeapOomDemo.java jvm/src/test/java/yier/bubu/jvm/HeapOomDemoTest.java
git commit -m "feat: add heap OOM lab"
```

## Task 3: Add GC Pressure Lab

**Files:**
- Create: `jvm/src/main/java/yier/bubu/jvm/GcPressureDemo.java`
- Test: `jvm/src/test/java/yier/bubu/jvm/GcPressureDemoTest.java`

- [ ] **Step 1: Write config tests**

Create `jvm/src/test/java/yier/bubu/jvm/GcPressureDemoTest.java`:

```java
package yier.bubu.jvm;

import org.junit.Assert;
import org.junit.Test;

public class GcPressureDemoTest {

    @Test
    public void configFrom_shouldParseExplicitValues() {
        GcPressureDemo.Config config = GcPressureDemo.Config.from(new String[]{
                "--seconds", "15",
                "--chunkKb", "512",
                "--retainEvery", "4",
                "--maxRetained", "32",
                "--reportEvery", "200"
        });

        Assert.assertEquals(15, config.seconds);
        Assert.assertEquals(512, config.chunkKb);
        Assert.assertEquals(4, config.retainEvery);
        Assert.assertEquals(32, config.maxRetained);
        Assert.assertEquals(200, config.reportEvery);
    }

    @Test
    public void configFrom_shouldNormalizeMinimums() {
        GcPressureDemo.Config config = GcPressureDemo.Config.from(new String[]{
                "--seconds", "0",
                "--chunkKb", "0",
                "--retainEvery", "0",
                "--maxRetained", "-1",
                "--reportEvery", "0"
        });

        Assert.assertEquals(1, config.seconds);
        Assert.assertEquals(1, config.chunkKb);
        Assert.assertEquals(1, config.retainEvery);
        Assert.assertEquals(0, config.maxRetained);
        Assert.assertEquals(1, config.reportEvery);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -pl jvm -am -Dtest=GcPressureDemoTest test
```

Expected: FAIL because `GcPressureDemo` does not exist.

- [ ] **Step 3: Implement GcPressureDemo**

Create `jvm/src/main/java/yier/bubu/jvm/GcPressureDemo.java`:

```java
package yier.bubu.jvm;

import java.util.ArrayDeque;
import java.util.Deque;

final class GcPressureDemo {
    private GcPressureDemo() {
    }

    static void run(String[] args) {
        Config config = Config.from(args);
        int chunkBytes = config.chunkKb * 1024;
        long endAt = System.nanoTime() + config.seconds * 1_000_000_000L;
        long allocations = 0;
        Deque<byte[]> retained = new ArrayDeque<byte[]>();

        System.out.println("[GcPressureDemo]");
        System.out.println("Allocating short-lived objects and retaining every Nth object.");
        System.out.println("seconds=" + config.seconds
                + " chunkKb=" + config.chunkKb
                + " retainEvery=" + config.retainEvery
                + " maxRetained=" + config.maxRetained
                + " reportEvery=" + config.reportEvery);
        System.out.println("Tip JDK 8:  -XX:+PrintGCDetails -XX:+PrintGCDateStamps -Xloggc:target/jvm-lab-gc.log");
        System.out.println("Tip JDK 9+: -Xlog:gc*:file=target/jvm-lab-gc.log:time,uptime,level,tags");
        System.out.println();

        while (System.nanoTime() < endAt) {
            byte[] block = new byte[chunkBytes];
            block[0] = (byte) allocations;
            allocations++;

            if (allocations % config.retainEvery == 0 && config.maxRetained > 0) {
                retained.addLast(block);
                while (retained.size() > config.maxRetained) {
                    retained.removeFirst();
                }
            }

            if (allocations % config.reportEvery == 0) {
                System.out.println("allocations=" + allocations
                        + " retained=" + retained.size()
                        + " approxAllocated=" + MemoryInspector.formatBytes(allocations * chunkBytes));
                MemoryInspector.printMemorySummary();
                System.out.println();
            }
        }

        System.out.println("Finished. allocations=" + allocations + " retained=" + retained.size());
    }

    static final class Config {
        final int seconds;
        final int chunkKb;
        final int retainEvery;
        final int maxRetained;
        final int reportEvery;

        private Config(int seconds, int chunkKb, int retainEvery, int maxRetained, int reportEvery) {
            this.seconds = seconds;
            this.chunkKb = chunkKb;
            this.retainEvery = retainEvery;
            this.maxRetained = maxRetained;
            this.reportEvery = reportEvery;
        }

        static Config from(String[] args) {
            return new Config(
                    Math.max(1, CliArgs.getInt(args, "--seconds", 60)),
                    Math.max(1, CliArgs.getInt(args, "--chunkKb", 256)),
                    Math.max(1, CliArgs.getInt(args, "--retainEvery", 8)),
                    Math.max(0, CliArgs.getInt(args, "--maxRetained", 256)),
                    Math.max(1, CliArgs.getInt(args, "--reportEvery", 1000))
            );
        }
    }
}
```

- [ ] **Step 4: Run GC pressure tests**

Run:

```bash
mvn -pl jvm -am -Dtest=GcPressureDemoTest test
```

Expected: PASS.

- [ ] **Step 5: Manually smoke test short GC pressure run**

Run:

```bash
mvn -pl jvm -am -DskipTests package
java -Xms128m -Xmx128m -cp jvm/target/classes yier.bubu.jvm.JvmLabApp gc-pressure --seconds 2 --chunkKb 64 --retainEvery 4 --maxRetained 16 --reportEvery 100
```

Expected: exits after roughly 2 seconds and prints `[GcPressureDemo]`.

- [ ] **Step 6: Commit**

```bash
git add jvm/src/main/java/yier/bubu/jvm/GcPressureDemo.java jvm/src/test/java/yier/bubu/jvm/GcPressureDemoTest.java
git commit -m "feat: add GC pressure lab"
```

## Task 4: Add Troubleshooting Failure Demos

**Files:**
- Create: `jvm/src/main/java/yier/bubu/jvm/HighCpuDemo.java`
- Create: `jvm/src/main/java/yier/bubu/jvm/StaticMemoryLeakDemo.java`
- Create: `jvm/src/main/java/yier/bubu/jvm/DeadlockDemo.java`
- Create: `jvm/src/main/java/yier/bubu/jvm/ThreadBlockDemo.java`
- Test: `jvm/src/test/java/yier/bubu/jvm/TroubleshootingDemoConfigTest.java`

- [ ] **Step 1: Write safe config tests**

Create `jvm/src/test/java/yier/bubu/jvm/TroubleshootingDemoConfigTest.java`:

```java
package yier.bubu.jvm;

import org.junit.Assert;
import org.junit.Test;

public class TroubleshootingDemoConfigTest {

    @Test
    public void highCpuConfigFrom_shouldParseValues() {
        HighCpuDemo.Config config = HighCpuDemo.Config.from(new String[]{
                "--threads", "3",
                "--seconds", "9"
        });

        Assert.assertEquals(3, config.threads);
        Assert.assertEquals(9, config.seconds);
    }

    @Test
    public void staticLeakConfigFrom_shouldParseValues() {
        StaticMemoryLeakDemo.Config config = StaticMemoryLeakDemo.Config.from(new String[]{
                "--mb", "64",
                "--chunkMb", "4",
                "--reportEvery", "2",
                "--sleepSeconds", "5"
        });

        Assert.assertEquals(64, config.totalMb);
        Assert.assertEquals(4, config.chunkMb);
        Assert.assertEquals(2, config.reportEvery);
        Assert.assertEquals(5, config.sleepSeconds);
    }

    @Test
    public void threadBlockConfigFrom_shouldParseValues() {
        ThreadBlockDemo.Config config = ThreadBlockDemo.Config.from(new String[]{
                "--waiters", "4",
                "--sleepSeconds", "11"
        });

        Assert.assertEquals(4, config.waiters);
        Assert.assertEquals(11, config.sleepSeconds);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -pl jvm -am -Dtest=TroubleshootingDemoConfigTest test
```

Expected: FAIL because troubleshooting demo classes do not exist.

- [ ] **Step 3: Implement HighCpuDemo**

Create `jvm/src/main/java/yier/bubu/jvm/HighCpuDemo.java`:

```java
package yier.bubu.jvm;

import java.util.ArrayList;
import java.util.List;

final class HighCpuDemo {
    private HighCpuDemo() {
    }

    static void run(String[] args) throws Exception {
        Config config = Config.from(args);
        System.out.println("[HighCpuDemo]");
        System.out.println("Starting busy-loop threads. threads=" + config.threads + " seconds=" + config.seconds);
        System.out.println("Use top -H -p <pid>, convert TID with printf \"%x\\n\" <tid>, then search nid in jstack.");

        List<Thread> threads = new ArrayList<Thread>();
        for (int i = 0; i < config.threads; i++) {
            Thread thread = new Thread(new BusyTask(), "jvm-lab-high-cpu-" + i);
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
        }

        Thread.sleep(config.seconds * 1000L);
        System.out.println("Finished sleep window. Daemon busy threads will stop when main exits. threads=" + threads.size());
    }

    static final class Config {
        final int threads;
        final int seconds;

        private Config(int threads, int seconds) {
            this.threads = threads;
            this.seconds = seconds;
        }

        static Config from(String[] args) {
            return new Config(
                    Math.max(1, CliArgs.getInt(args, "--threads", 1)),
                    Math.max(1, CliArgs.getInt(args, "--seconds", 120))
            );
        }
    }

    private static final class BusyTask implements Runnable {
        @Override
        public void run() {
            long value = 0;
            while (true) {
                value = value * 31 + System.nanoTime();
                if (value == Long.MIN_VALUE) {
                    System.out.println(value);
                }
            }
        }
    }
}
```

- [ ] **Step 4: Implement StaticMemoryLeakDemo**

Create `jvm/src/main/java/yier/bubu/jvm/StaticMemoryLeakDemo.java`:

```java
package yier.bubu.jvm;

import java.util.ArrayList;
import java.util.List;

final class StaticMemoryLeakDemo {
    private static final List<byte[]> RETAINED = new ArrayList<byte[]>();

    private StaticMemoryLeakDemo() {
    }

    static void run(String[] args) throws Exception {
        Config config = Config.from(args);
        int chunkBytes = config.chunkMb * 1024 * 1024;
        int chunks = Math.max(1, config.totalMb / config.chunkMb);

        System.out.println("[StaticMemoryLeakDemo]");
        System.out.println("Growing a static List<byte[]> to simulate heap retention.");
        System.out.println("totalMb=" + config.totalMb + " chunkMb=" + config.chunkMb + " chunks=" + chunks);
        System.out.println("Use jcmd <pid> GC.class_histogram and heap dump tools to observe byte[] retained by a static field.");
        System.out.println();

        for (int i = 0; i < chunks; i++) {
            byte[] block = new byte[chunkBytes];
            block[0] = (byte) i;
            RETAINED.add(block);

            if ((i + 1) % config.reportEvery == 0 || i == chunks - 1) {
                System.out.println("retainedChunks=" + RETAINED.size()
                        + " approxRetained=" + MemoryInspector.formatBytes((long) RETAINED.size() * chunkBytes));
                MemoryInspector.printMemorySummary();
                System.out.println();
            }
        }

        if (config.sleepSeconds > 0) {
            System.out.println("Sleeping " + config.sleepSeconds + "s for tool attachment.");
            Thread.sleep(config.sleepSeconds * 1000L);
        }
    }

    static final class Config {
        final int totalMb;
        final int chunkMb;
        final int reportEvery;
        final int sleepSeconds;

        private Config(int totalMb, int chunkMb, int reportEvery, int sleepSeconds) {
            this.totalMb = totalMb;
            this.chunkMb = chunkMb;
            this.reportEvery = reportEvery;
            this.sleepSeconds = sleepSeconds;
        }

        static Config from(String[] args) {
            return new Config(
                    Math.max(1, CliArgs.getInt(args, "--mb", 64)),
                    Math.max(1, CliArgs.getInt(args, "--chunkMb", 1)),
                    Math.max(1, CliArgs.getInt(args, "--reportEvery", 8)),
                    Math.max(0, CliArgs.getInt(args, "--sleepSeconds", 120))
            );
        }
    }
}
```

- [ ] **Step 5: Implement DeadlockDemo**

Create `jvm/src/main/java/yier/bubu/jvm/DeadlockDemo.java`:

```java
package yier.bubu.jvm;

final class DeadlockDemo {
    private static final Object A = new Object();
    private static final Object B = new Object();

    private DeadlockDemo() {
    }

    static void run(String[] args) throws Exception {
        int sleepSeconds = Math.max(1, CliArgs.getInt(args, "--sleepSeconds", 600));
        System.out.println("[DeadlockDemo]");
        System.out.println("Creating a monitor deadlock. sleepSeconds=" + sleepSeconds);
        System.out.println("Use jstack <pid> and search for 'Found one Java-level deadlock'.");

        Thread a = new Thread(new LockTask(A, B), "jvm-lab-deadlock-a");
        Thread b = new Thread(new LockTask(B, A), "jvm-lab-deadlock-b");
        a.start();
        b.start();

        Thread.sleep(sleepSeconds * 1000L);
    }

    private static final class LockTask implements Runnable {
        private final Object first;
        private final Object second;

        private LockTask(Object first, Object second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void run() {
            synchronized (first) {
                sleepQuietly(1000L);
                synchronized (second) {
                    System.out.println("unreachable");
                }
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 6: Implement ThreadBlockDemo**

Create `jvm/src/main/java/yier/bubu/jvm/ThreadBlockDemo.java`:

```java
package yier.bubu.jvm;

final class ThreadBlockDemo {
    private static final Object MONITOR = new Object();

    private ThreadBlockDemo() {
    }

    static void run(String[] args) throws Exception {
        Config config = Config.from(args);
        System.out.println("[ThreadBlockDemo]");
        System.out.println("Creating one lock holder and BLOCKED waiter threads. waiters=" + config.waiters + " sleepSeconds=" + config.sleepSeconds);
        System.out.println("Use jstack <pid> and inspect jvm-lab-thread-block-* thread states.");

        Thread holder = new Thread(new Holder(config.sleepSeconds), "jvm-lab-thread-block-holder");
        holder.start();
        Thread.sleep(300L);

        for (int i = 0; i < config.waiters; i++) {
            Thread waiter = new Thread(new Waiter(), "jvm-lab-thread-block-waiter-" + i);
            waiter.start();
        }

        holder.join();
    }

    static final class Config {
        final int waiters;
        final int sleepSeconds;

        private Config(int waiters, int sleepSeconds) {
            this.waiters = waiters;
            this.sleepSeconds = sleepSeconds;
        }

        static Config from(String[] args) {
            return new Config(
                    Math.max(1, CliArgs.getInt(args, "--waiters", 3)),
                    Math.max(1, CliArgs.getInt(args, "--sleepSeconds", 120))
            );
        }
    }

    private static final class Holder implements Runnable {
        private final int sleepSeconds;

        private Holder(int sleepSeconds) {
            this.sleepSeconds = sleepSeconds;
        }

        @Override
        public void run() {
            synchronized (MONITOR) {
                sleepQuietly(sleepSeconds * 1000L);
            }
        }
    }

    private static final class Waiter implements Runnable {
        @Override
        public void run() {
            synchronized (MONITOR) {
                System.out.println(Thread.currentThread().getName() + " acquired monitor");
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 7: Run troubleshooting config tests**

Run:

```bash
mvn -pl jvm -am -Dtest=TroubleshootingDemoConfigTest test
```

Expected: PASS.

- [ ] **Step 8: Smoke test short-lived troubleshooting commands**

Run:

```bash
mvn -pl jvm -am -DskipTests package
java -cp jvm/target/classes yier.bubu.jvm.JvmLabApp high-cpu --threads 1 --seconds 1
java -Xms128m -Xmx128m -cp jvm/target/classes yier.bubu.jvm.JvmLabApp static-leak --mb 4 --chunkMb 1 --reportEvery 2 --sleepSeconds 0
java -cp jvm/target/classes yier.bubu.jvm.JvmLabApp thread-block --waiters 1 --sleepSeconds 1
```

Expected: each command exits quickly and prints its scenario header. Do not smoke test `deadlock` in automation because it intentionally creates permanently blocked non-daemon threads until the process exits or is killed.

- [ ] **Step 9: Commit**

```bash
git add jvm/src/main/java/yier/bubu/jvm/HighCpuDemo.java jvm/src/main/java/yier/bubu/jvm/StaticMemoryLeakDemo.java jvm/src/main/java/yier/bubu/jvm/DeadlockDemo.java jvm/src/main/java/yier/bubu/jvm/ThreadBlockDemo.java jvm/src/test/java/yier/bubu/jvm/TroubleshootingDemoConfigTest.java
git commit -m "feat: add JVM troubleshooting demos"
```

## Task 5: Add Memory and GC Lab Documentation

**Files:**
- Create: `jvm/docs/labs/jl-02-memory-gc-lab.md`
- Create: `jvm/docs/runbooks/jl-02-heap-oom-runbook.md`
- Create: `jvm/docs/runbooks/jl-02-direct-memory-oom-runbook.md`
- Create: `jvm/docs/runbooks/jl-02-metaspace-oom-runbook.md`
- Create: `jvm/docs/reports/jl-02-heap-oom-report-template.md`
- Modify: `jvm/docs/jvm-memory.md`

- [ ] **Step 1: Create memory and GC lab overview**

Create `jvm/docs/labs/jl-02-memory-gc-lab.md`:

```markdown
# JL-02 内存模型与 GC 实验

父级规格：`docs/superpowers/specs/jvm-lab/jl-02-memory-gc-spec.md`

## 1. 实验目标

本实验组用于制造和观察 JVM 内存问题：Heap OOM、Direct Memory OOM、Metaspace OOM、StackOverflowError 和 GC pressure。每个实验都按“构造问题 -> 运行程序 -> 使用工具观察 -> 分析机制 -> 修改参数或代码 -> 验证效果 -> 写复盘”的闭环执行。

## 2. 编译

```bash
mvn -pl jvm -am -DskipTests package
```

## 3. 实验入口

```bash
java -cp jvm/target/classes yier.bubu.jvm.JvmLabApp help
```

## 4. 实验清单

| 编号 | 场景 | 入口 | 重点证据 |
| --- | --- | --- | --- |
| JL-02-LAB-01 | Heap OOM | `heap-oom` | heap dump、GC Roots、`byte[]` retained heap |
| JL-02-LAB-02 | Direct Memory OOM | `direct-oom` | BufferPoolMXBean、NMT、直接内存上限 |
| JL-02-LAB-03 | Metaspace OOM | `metaspace-oom` | Metaspace pool、ClassLoader 可达性 |
| JL-02-LAB-04 | StackOverflowError | `stack-overflow` | 递归深度、`-Xss` 差异 |
| JL-02-LAB-05 | GC pressure | `gc-pressure` | GC 日志、`jstat -gcutil`、晋升和分配速率 |

## 5. 运行命令

### 5.1 Heap OOM

```bash
java -Xms64m -Xmx64m \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=target/jvm-lab-heap.hprof \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp heap-oom --mb 128 --chunkMb 1 --reportEvery 8
```

### 5.2 Direct Memory OOM

```bash
java -XX:MaxDirectMemorySize=64m \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp direct-oom --mb 96 --chunkMb 4 --touch true --reportEvery 4
```

### 5.3 Metaspace OOM

```bash
java -XX:MaxMetaspaceSize=64m \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp metaspace-oom --count 20000 --reportEvery 1000
```

### 5.4 StackOverflowError

```bash
java -Xss256k \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp stack-overflow
```

### 5.5 GC pressure

JDK 8：

```bash
java -Xms128m -Xmx128m \
  -XX:+UseG1GC \
  -XX:+PrintGCDetails \
  -XX:+PrintGCDateStamps \
  -Xloggc:target/jvm-lab-gc.log \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp gc-pressure --seconds 60 --chunkKb 256 --retainEvery 8 --maxRetained 256
```

JDK 9+：

```bash
java -Xms128m -Xmx128m \
  -XX:+UseG1GC \
  -Xlog:gc*:file=target/jvm-lab-gc.log:time,uptime,level,tags \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp gc-pressure --seconds 60 --chunkKb 256 --retainEvery 8 --maxRetained 256
```

## 6. 产物处理

`target/*.hprof`、`target/*.log` 和本地工具导出的分析文件不提交到仓库。复盘只提交必要的结论、关键小片段和截图说明。
```

- [ ] **Step 2: Create Heap OOM runbook**

Create `jvm/docs/runbooks/jl-02-heap-oom-runbook.md`:

```markdown
# JL-02-LAB-01 Heap OOM 排查 Runbook

## 1. 启动实验

```bash
mvn -pl jvm -am -DskipTests package

java -Xms64m -Xmx64m \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=target/jvm-lab-heap.hprof \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp heap-oom --mb 128 --chunkMb 1 --reportEvery 8
```

## 2. 预期现象

程序持续把 `byte[]` 放入 `ArrayList`，最终抛出 `java.lang.OutOfMemoryError: Java heap space`，并在 `target/jvm-lab-heap.hprof` 生成 heap dump。

## 3. 观察命令

```bash
jcmd <pid> GC.heap_info
jcmd <pid> GC.class_histogram
jmap -dump:live,format=b,file=target/jvm-lab-live.hprof <pid>
```

## 4. MAT / VisualVM 观察点

- Dominator Tree 中 `byte[]` 占用最大。
- `ArrayList.elementData` 持有大量 `byte[]`。
- `ArrayList` 由 `HeapOomDemo.run` 的局部变量链路保持可达。

## 5. 根因判断

这不是 GC 不工作，而是对象仍然从 GC Roots 可达。集合持续持有引用，导致 `byte[]` 无法回收。

## 6. 修复方向

- 给集合或缓存设置容量上限。
- 使用淘汰策略。
- 分批处理并释放引用。
- 避免把大对象挂到长生命周期静态字段或线程局部变量上。
```

- [ ] **Step 3: Create Direct Memory OOM runbook**

Create `jvm/docs/runbooks/jl-02-direct-memory-oom-runbook.md`:

```markdown
# JL-02-LAB-02 Direct Memory OOM 排查 Runbook

## 1. 启动实验

```bash
mvn -pl jvm -am -DskipTests package

java -XX:MaxDirectMemorySize=64m \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp direct-oom --mb 96 --chunkMb 4 --touch true --reportEvery 4
```

## 2. 预期现象

程序使用 `ByteBuffer.allocateDirect()` 分配直接内存并持有引用。当直接内存超过 `MaxDirectMemorySize` 后，可能抛出 direct buffer memory 相关 OOM。

## 3. 观察命令

```bash
jcmd <pid> VM.native_memory summary
jcmd <pid> GC.heap_info
jcmd <pid> GC.class_histogram
```

Native Memory Tracking 需要启动时开启：

```bash
-XX:+UnlockDiagnosticVMOptions -XX:NativeMemoryTracking=summary
```

## 4. 关键结论

直接内存属于本地内存，不是 Java heap。Heap dump 能看到 `DirectByteBuffer` 对象和引用链，但不一定直接展示全部堆外内存占用。

## 5. 修复方向

- 控制 direct buffer 池大小。
- 避免无界缓存 direct buffer。
- 检查 Netty 等框架的 direct memory 配置。
- 结合 RSS、NMT 和 BufferPoolMXBean 判断堆外占用。
```

- [ ] **Step 4: Create Metaspace OOM runbook**

Create `jvm/docs/runbooks/jl-02-metaspace-oom-runbook.md`:

```markdown
# JL-02-LAB-03 Metaspace OOM 排查 Runbook

## 1. 启动实验

```bash
mvn -pl jvm -am -DskipTests package

java -XX:MaxMetaspaceSize=64m \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp metaspace-oom --count 20000 --reportEvery 1000
```

## 2. 预期现象

程序动态定义大量类，并持有 `Class` 引用和定义这些类的 ClassLoader。元空间持续增长，可能抛出 `java.lang.OutOfMemoryError: Metaspace`。

## 3. 观察命令

```bash
jcmd <pid> GC.class_stats
jcmd <pid> GC.class_histogram
jcmd <pid> VM.classloader_stats
jcmd <pid> GC.heap_info
```

不同 JDK 支持的 `jcmd` 子命令会有差异，以 `jcmd <pid> help` 为准。

## 4. 根因判断

类元数据主要在 Metaspace 中。类卸载依赖定义类的 ClassLoader 不再可达；如果 ClassLoader 泄漏，类元数据也无法释放。

## 5. 修复方向

- 避免重复生成无限数量的类。
- 清理持有 ClassLoader 的静态集合、线程上下文类加载器和缓存。
- 检查热部署、插件系统和动态代理生成策略。
```

- [ ] **Step 5: Create Heap OOM report template**

Create `jvm/docs/reports/jl-02-heap-oom-report-template.md`:

```markdown
# JL-02-LAB-01 Heap OOM 复盘模板

## 1. 现象

程序运行后抛出 `java.lang.OutOfMemoryError: Java heap space`。

## 2. JVM 参数

```text
-Xms64m -Xmx64m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=target/jvm-lab-heap.hprof
```

## 3. 排查工具

- `jcmd`
- `jmap`
- MAT 或 VisualVM

## 4. 关键证据

- 最大对象类型：
- Retained Heap：
- GC Roots：
- 引用链：

## 5. 根因

集合持续持有对象引用，导致对象仍然可达，GC 无法回收。

## 6. 解决方案

- 限制集合大小。
- 使用淘汰策略。
- 分批处理后清理引用。

## 7. 验证方式

重新运行程序，观察 heap 使用是否稳定，或确认新的 heap dump 中 retained heap 不再无界增长。

## 8. 总结

不是 GC 不工作，而是对象仍然可达。
```

- [ ] **Step 6: Link lab docs from JVM memory doc**

Modify `jvm/docs/jvm-memory.md`. After the existing demo command section, add:

```markdown
### 3.5 JVM 实战靶场：内存与 GC

更完整的故障靶场见：

- [JL-02 内存模型与 GC 实验](labs/jl-02-memory-gc-lab.md)
- [JL-02-LAB-01 Heap OOM 排查 Runbook](runbooks/jl-02-heap-oom-runbook.md)
- [JL-02-LAB-02 Direct Memory OOM 排查 Runbook](runbooks/jl-02-direct-memory-oom-runbook.md)
- [JL-02-LAB-03 Metaspace OOM 排查 Runbook](runbooks/jl-02-metaspace-oom-runbook.md)
```

- [ ] **Step 7: Commit**

```bash
git add jvm/docs/labs/jl-02-memory-gc-lab.md jvm/docs/runbooks/jl-02-heap-oom-runbook.md jvm/docs/runbooks/jl-02-direct-memory-oom-runbook.md jvm/docs/runbooks/jl-02-metaspace-oom-runbook.md jvm/docs/reports/jl-02-heap-oom-report-template.md jvm/docs/jvm-memory.md
git commit -m "docs: add JVM memory GC lab runbooks"
```

## Task 6: Add Troubleshooting Documentation

**Files:**
- Create: `jvm/docs/labs/jl-05-troubleshooting-lab.md`
- Create: `jvm/docs/runbooks/jl-05-high-cpu-runbook.md`
- Create: `jvm/docs/runbooks/jl-05-memory-leak-runbook.md`
- Create: `jvm/docs/runbooks/jl-05-full-gc-runbook.md`
- Create: `jvm/docs/runbooks/jl-05-thread-block-runbook.md`
- Create: `jvm/docs/reports/jl-05-troubleshooting-report-template.md`

- [ ] **Step 1: Create troubleshooting lab overview**

Create `jvm/docs/labs/jl-05-troubleshooting-lab.md`:

```markdown
# JL-05 线上故障排查实验

父级规格：`docs/superpowers/specs/jvm-lab/jl-05-troubleshooting-spec.md`

## 1. 实验目标

本实验组模拟线上 JVM 故障：高 CPU、静态集合内存泄漏、死锁、线程阻塞、频繁 GC 和直接内存 OOM。目标是从系统现象出发，拿到 JVM 证据，再定位到代码根因。

## 2. 编译

```bash
mvn -pl jvm -am -DskipTests package
```

## 3. 实验清单

| 编号 | 场景 | 入口 | 主要工具 |
| --- | --- | --- | --- |
| JL-05-LAB-01 | 高 CPU | `high-cpu` | `top -H`、`printf`、`jstack` |
| JL-05-LAB-02 | 静态集合内存泄漏 | `static-leak` | `jcmd`、`jmap`、MAT |
| JL-05-LAB-04 | 死锁 | `deadlock` | `jstack` |
| JL-05-LAB-05 | 线程阻塞 | `thread-block` | `jstack` |
| JL-05-LAB-06 | 频繁 GC | `gc-pressure` | `jstat`、GC log |
| JL-05-LAB-07 | Direct Memory OOM | `direct-oom` | BufferPoolMXBean、NMT |

`JL-05-LAB-03 ThreadLocal 泄漏` 留到后续批次，避免第 1 批同时引入过多线程生命周期场景。

## 4. 入口命令

```bash
java -cp jvm/target/classes yier.bubu.jvm.JvmLabApp help
```

## 5. 排查闭环

```text
现象 -> OS 指标 -> JVM 证据 -> 代码位置 -> 根因 -> 修复方案 -> 验证
```
```

- [ ] **Step 2: Create high CPU runbook**

Create `jvm/docs/runbooks/jl-05-high-cpu-runbook.md`:

```markdown
# JL-05-LAB-01 高 CPU 排查 Runbook

## 1. 启动实验

```bash
mvn -pl jvm -am -DskipTests package

java -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp high-cpu --threads 1 --seconds 120
```

## 2. 排查命令

```bash
top
top -H -p <pid>
printf "%x\n" <tid>
jstack <pid> > target/jvm-lab-thread.txt
```

## 3. 判断方法

1. 在 `top -H -p <pid>` 中找到 CPU 高的线程 ID。
2. 用 `printf "%x\n" <tid>` 转成十六进制。
3. 在 `jstack` 输出中搜索 `nid=0x...`。
4. 观察线程名应类似 `jvm-lab-high-cpu-0`。
5. 栈顶应落在 `HighCpuDemo$BusyTask.run` 附近。

## 4. 修复方向

- 给循环增加退出条件。
- 限制线程数。
- 对真实业务场景检查正则回溯、序列化、加密计算、无限重试等热点。
```

- [ ] **Step 3: Create memory leak runbook**

Create `jvm/docs/runbooks/jl-05-memory-leak-runbook.md`:

```markdown
# JL-05-LAB-02 静态集合内存泄漏 Runbook

## 1. 启动实验

```bash
mvn -pl jvm -am -DskipTests package

java -Xms128m -Xmx128m \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp static-leak --mb 96 --chunkMb 1 --reportEvery 8 --sleepSeconds 300
```

## 2. 排查命令

```bash
jcmd <pid> GC.heap_info
jcmd <pid> GC.class_histogram
jmap -dump:live,format=b,file=target/jvm-lab-static-leak.hprof <pid>
```

## 3. 观察点

- `byte[]` 数量和占用靠前。
- heap dump 中 `StaticMemoryLeakDemo.RETAINED` 持有对象。
- GC 后对象仍然可达。

## 4. 根因

静态集合生命周期接近整个 JVM 进程。只要集合不清理，其中对象就不会被 GC 回收。

## 5. 修复方向

- 限制缓存大小。
- 使用过期和淘汰策略。
- 在生命周期结束时清理静态集合。
```

- [ ] **Step 4: Create Full GC / GC pressure runbook**

Create `jvm/docs/runbooks/jl-05-full-gc-runbook.md`:

```markdown
# JL-05-LAB-06 频繁 GC 排查 Runbook

## 1. 启动实验

JDK 8：

```bash
mvn -pl jvm -am -DskipTests package

java -Xms128m -Xmx128m \
  -XX:+UseG1GC \
  -XX:+PrintGCDetails \
  -XX:+PrintGCDateStamps \
  -Xloggc:target/jvm-lab-gc.log \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp gc-pressure --seconds 120 --chunkKb 256 --retainEvery 4 --maxRetained 512
```

JDK 9+：

```bash
java -Xms128m -Xmx128m \
  -XX:+UseG1GC \
  -Xlog:gc*:file=target/jvm-lab-gc.log:time,uptime,level,tags \
  -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp gc-pressure --seconds 120 --chunkKb 256 --retainEvery 4 --maxRetained 512
```

## 2. 排查命令

```bash
jstat -gcutil <pid> 1000
jcmd <pid> GC.heap_info
jcmd <pid> VM.flags
```

## 3. 判断问题

- Young GC 是否过于频繁。
- Old 区是否持续上涨。
- 是否存在对象大量晋升。
- 是否堆太小。
- 是否有保留对象过多导致回收效果差。

## 4. 修复方向

- 降低分配速率。
- 减少长生命周期对象。
- 调整缓存策略。
- 根据证据调整堆大小和 GC 参数。
```

- [ ] **Step 5: Create thread block runbook**

Create `jvm/docs/runbooks/jl-05-thread-block-runbook.md`:

```markdown
# JL-05-LAB-04/05 死锁与线程阻塞 Runbook

## 1. 死锁实验

```bash
mvn -pl jvm -am -DskipTests package

java -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp deadlock --sleepSeconds 600
```

排查：

```bash
jps
jstack <pid> > target/jvm-lab-deadlock.txt
```

观察 `Found one Java-level deadlock`，并检查 `jvm-lab-deadlock-a` 和 `jvm-lab-deadlock-b` 分别持有什么锁、等待什么锁。

## 2. 线程阻塞实验

```bash
java -cp jvm/target/classes \
  yier.bubu.jvm.JvmLabApp thread-block --waiters 3 --sleepSeconds 120
```

排查：

```bash
jstack <pid> > target/jvm-lab-blocked.txt
```

观察：

- `jvm-lab-thread-block-holder` 持有 monitor。
- `jvm-lab-thread-block-waiter-*` 处于 `BLOCKED`。

## 3. 修复方向

- 固定加锁顺序，避免环形等待。
- 缩小同步块。
- 使用超时锁或显式锁时确保释放。
- 降低共享锁竞争。
```

- [ ] **Step 6: Create troubleshooting report template**

Create `jvm/docs/reports/jl-05-troubleshooting-report-template.md`:

```markdown
# JL-05 故障排查复盘模板

## 1. 场景

- 实验编号：
- 实验入口：
- JDK 版本：
- JVM 参数：

## 2. 用户侧现象

例如 CPU 飙高、接口无响应、内存持续上涨、频繁 GC、线程卡住。

## 3. OS 层证据

- `top`：
- `top -H -p <pid>`：
- RSS / CPU / load：

## 4. JVM 层证据

- `jstack`：
- `jcmd`：
- `jstat`：
- GC 日志：
- heap dump：

## 5. 代码位置

说明线程名、栈帧、类名和方法名。

## 6. 根因

根因分类：死循环、锁竞争、缓存无界、ThreadLocal 未清理、队列积压、堆太小、分配速率过高、直接内存泄漏。

## 7. 修复方案

写清楚代码修改、参数调整或架构约束。

## 8. 验证

重新运行实验或修复版本，说明指标如何变化。
```

- [ ] **Step 7: Commit**

```bash
git add jvm/docs/labs/jl-05-troubleshooting-lab.md jvm/docs/runbooks/jl-05-high-cpu-runbook.md jvm/docs/runbooks/jl-05-memory-leak-runbook.md jvm/docs/runbooks/jl-05-full-gc-runbook.md jvm/docs/runbooks/jl-05-thread-block-runbook.md jvm/docs/reports/jl-05-troubleshooting-report-template.md
git commit -m "docs: add JVM troubleshooting runbooks"
```

## Task 7: Publish Lab Docs in MkDocs

**Files:**
- Modify: `mkdocs/docs/jvm/index.md`
- Modify: `mkdocs/mkdocs.yml`

- [ ] **Step 1: Update JVM index**

Modify `mkdocs/docs/jvm/index.md`. Add these entries under `## Topics` after `JVM Memory`:

```markdown
- [JL-02 内存模型与 GC 实验](content/labs/jl-02-memory-gc-lab.md)
- [JL-05 线上故障排查实验](content/labs/jl-05-troubleshooting-lab.md)
```

Add this note under `## Notes`:

```markdown
- JVM Lab pages are manual failure labs. Run OOM, CPU, deadlock, and blocking commands only in a controlled local terminal.
```

- [ ] **Step 2: Update MkDocs nav**

Modify the JVM section in `mkdocs/mkdocs.yml`. After `JVM Memory: jvm/content/jvm-memory.md`, add:

```yaml
      - JVM Lab:
          - JL-02 内存模型与 GC 实验: jvm/content/labs/jl-02-memory-gc-lab.md
          - JL-05 线上故障排查实验: jvm/content/labs/jl-05-troubleshooting-lab.md
          - Runbooks:
              - Heap OOM 排查: jvm/content/runbooks/jl-02-heap-oom-runbook.md
              - Direct Memory OOM 排查: jvm/content/runbooks/jl-02-direct-memory-oom-runbook.md
              - Metaspace OOM 排查: jvm/content/runbooks/jl-02-metaspace-oom-runbook.md
              - 高 CPU 排查: jvm/content/runbooks/jl-05-high-cpu-runbook.md
              - 静态集合内存泄漏排查: jvm/content/runbooks/jl-05-memory-leak-runbook.md
              - 频繁 GC 排查: jvm/content/runbooks/jl-05-full-gc-runbook.md
              - 死锁与线程阻塞排查: jvm/content/runbooks/jl-05-thread-block-runbook.md
          - Reports:
              - Heap OOM 复盘模板: jvm/content/reports/jl-02-heap-oom-report-template.md
              - 故障排查复盘模板: jvm/content/reports/jl-05-troubleshooting-report-template.md
```

- [ ] **Step 3: Run MkDocs build**

Run:

```bash
mkdocs build -f mkdocs/mkdocs.yml
```

Expected: build exits with code 0.

- [ ] **Step 4: Commit**

```bash
git add mkdocs/docs/jvm/index.md mkdocs/mkdocs.yml
git commit -m "docs: publish JVM lab navigation"
```

## Task 8: Final Verification

**Files:**
- Verify all changed Java and Markdown files.

- [ ] **Step 1: Run JVM module tests**

Run:

```bash
mvn -pl jvm -am test
```

Expected: all `jvm` module tests pass. Dangerous OOM, CPU, deadlock, and blocking scenarios must not run during tests.

- [ ] **Step 2: Run package build**

Run:

```bash
mvn -pl jvm -am -DskipTests package
```

Expected: build exits with code 0 and produces `jvm/target/classes`.

- [ ] **Step 3: Run CLI help**

Run:

```bash
java -cp jvm/target/classes yier.bubu.jvm.JvmLabApp help
```

Expected: output lists `heap-oom`, `direct-oom`, `metaspace-oom`, `stack-overflow`, `gc-pressure`, `high-cpu`, `static-leak`, `deadlock`, and `thread-block`.

- [ ] **Step 4: Run short safe smoke commands**

Run:

```bash
java -Xms128m -Xmx128m -cp jvm/target/classes yier.bubu.jvm.JvmLabApp heap-oom --mb 4 --chunkMb 1 --reportEvery 2
java -Xms128m -Xmx128m -cp jvm/target/classes yier.bubu.jvm.JvmLabApp gc-pressure --seconds 1 --chunkKb 64 --retainEvery 4 --maxRetained 8 --reportEvery 100
java -cp jvm/target/classes yier.bubu.jvm.JvmLabApp high-cpu --threads 1 --seconds 1
java -Xms128m -Xmx128m -cp jvm/target/classes yier.bubu.jvm.JvmLabApp static-leak --mb 4 --chunkMb 1 --reportEvery 2 --sleepSeconds 0
java -cp jvm/target/classes yier.bubu.jvm.JvmLabApp thread-block --waiters 1 --sleepSeconds 1
```

Expected: each command exits quickly with its scenario header.

- [ ] **Step 5: Run docs build**

Run:

```bash
mkdocs build -f mkdocs/mkdocs.yml
```

Expected: build exits with code 0.

- [ ] **Step 6: Inspect git status**

Run:

```bash
git status --short
```

Expected: only intended source files are modified. No `.hprof`, `.jfr`, `.log`, `mkdocs/site/`, or `target/` artifacts are staged.

## Spec Coverage Review

- `JL-02-LAB-01 Heap OOM`: Task 2 and Task 5.
- `JL-02-LAB-02 Direct Memory OOM`: existing demo, Task 1 CLI alias, Task 5 runbook.
- `JL-02-LAB-03 Metaspace OOM`: existing demo, Task 1 CLI alias, Task 5 runbook.
- `JL-02-LAB-04 StackOverflowError`: existing demo, Task 1 CLI alias, Task 5 overview.
- `JL-02-LAB-05 GC 日志分析`: Task 3 and Task 5/6 full GC runbook.
- `JL-05-LAB-01 高 CPU`: Task 4 and Task 6 runbook.
- `JL-05-LAB-02 静态集合内存泄漏`: Task 4 and Task 6 runbook.
- `JL-05-LAB-03 ThreadLocal 泄漏`: deferred explicitly in Task 6 overview.
- `JL-05-LAB-04 死锁`: Task 4 and Task 6 thread runbook.
- `JL-05-LAB-05 线程阻塞`: Task 4 and Task 6 thread runbook.
- `JL-05-LAB-06 频繁 GC`: Task 3 and Task 6 full GC runbook.
- `JL-05-LAB-07 Direct Memory OOM`: existing demo, Task 1 CLI alias, Task 5 runbook.

## Execution Notes

- Run commands from repository root.
- Do not run intentional OOM commands during automated verification.
- Do not stage heap dumps, GC logs, JFR files, `target/`, or `mkdocs/site/`.
- If a commit command is inappropriate for the current workflow, skip the commit step and keep the same file grouping in the final diff.
