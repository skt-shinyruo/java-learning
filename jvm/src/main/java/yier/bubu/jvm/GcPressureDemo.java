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
