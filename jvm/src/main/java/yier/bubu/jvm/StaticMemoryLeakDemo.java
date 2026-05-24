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
