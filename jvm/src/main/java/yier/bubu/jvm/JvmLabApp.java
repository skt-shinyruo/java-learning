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
