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
