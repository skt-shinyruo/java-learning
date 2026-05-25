package yier.bubu.jvm;

import java.util.concurrent.CountDownLatch;

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

        CountDownLatch ready = new CountDownLatch(2);
        Thread a = new Thread(new LockTask(A, B, ready), "jvm-lab-deadlock-a");
        Thread b = new Thread(new LockTask(B, A, ready), "jvm-lab-deadlock-b");
        a.start();
        b.start();

        Thread.sleep(sleepSeconds * 1000L);
    }

    private static final class LockTask implements Runnable {
        private final Object first;
        private final Object second;
        private final CountDownLatch ready;

        private LockTask(Object first, Object second, CountDownLatch ready) {
            this.first = first;
            this.second = second;
            this.ready = ready;
        }

        @Override
        public void run() {
            synchronized (first) {
                ready.countDown();
                awaitReady(ready);
                synchronized (second) {
                    System.out.println("unreachable");
                }
            }
        }
    }

    private static void awaitReady(CountDownLatch ready) {
        try {
            ready.await();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
