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
