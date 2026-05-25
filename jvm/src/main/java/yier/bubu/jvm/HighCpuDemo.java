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
