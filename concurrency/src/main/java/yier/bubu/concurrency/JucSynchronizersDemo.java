package yier.bubu.concurrency;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CountDownLatch、CyclicBarrier、Semaphore 的小型可测试示例。
 */
public final class JucSynchronizersDemo {
    private static final long WAIT_TIMEOUT_MILLIS = 1000;

    private JucSynchronizersDemo() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("CountDownLatch completed workers: " + waitForWorkersWithCountDownLatch(4));

        PhasedRunResult phasedRun = synchronizePhasesWithCyclicBarrier(3, 2);
        System.out.println("CyclicBarrier arrivals: " + phasedRun.getArrivals()
                + ", completed phases: " + phasedRun.getCompletedPhases());

        SemaphoreRunResult semaphoreRun = limitConcurrentAccessWithSemaphore(8, 2);
        System.out.println("Semaphore completed tasks: " + semaphoreRun.getCompletedTasks()
                + ", max concurrent: " + semaphoreRun.getMaxConcurrent());
    }

    public static int waitForWorkersWithCountDownLatch(int workers) throws InterruptedException {
        validatePositive(workers, "workers");

        AtomicInteger completedWorkers = new AtomicInteger(0);
        CountDownLatch done = new CountDownLatch(workers);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            for (int i = 0; i < workers; i++) {
                executor.execute(() -> {
                    try {
                        completedWorkers.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            await(done, "Timed out waiting for CountDownLatch workers");
            return completedWorkers.get();
        } finally {
            shutdown(executor);
        }
    }

    public static PhasedRunResult synchronizePhasesWithCyclicBarrier(int parties, int phases)
            throws InterruptedException {
        validatePositive(parties, "parties");
        validatePositive(phases, "phases");

        AtomicInteger arrivals = new AtomicInteger(0);
        AtomicInteger completedPhases = new AtomicInteger(0);
        AtomicReference<Exception> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(parties);
        CyclicBarrier barrier = new CyclicBarrier(parties, completedPhases::incrementAndGet);
        ExecutorService executor = Executors.newFixedThreadPool(parties);

        try {
            for (int i = 0; i < parties; i++) {
                executor.execute(() -> {
                    try {
                        for (int phase = 0; phase < phases; phase++) {
                            arrivals.incrementAndGet();
                            barrier.await(WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                        }
                    } catch (Exception e) {
                        restoreInterruptIfNeeded(e);
                        failure.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                });
            }

            await(done, "Timed out waiting for CyclicBarrier workers");
            rethrowIfFailed(failure, "CyclicBarrier demo failed");
            return new PhasedRunResult(arrivals.get(), completedPhases.get());
        } finally {
            shutdown(executor);
        }
    }

    public static SemaphoreRunResult limitConcurrentAccessWithSemaphore(int tasks, int permits)
            throws InterruptedException {
        validatePositive(tasks, "tasks");
        validatePositive(permits, "permits");

        Semaphore semaphore = new Semaphore(permits);
        AtomicInteger active = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger completedTasks = new AtomicInteger(0);
        AtomicReference<Exception> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks);
        ExecutorService executor = Executors.newFixedThreadPool(tasks);

        try {
            for (int i = 0; i < tasks; i++) {
                executor.execute(() -> {
                    try {
                        start.await();
                        semaphore.acquire();
                        try {
                            int current = active.incrementAndGet();
                            updateMax(maxConcurrent, current);
                            TimeUnit.MILLISECONDS.sleep(20);
                            completedTasks.incrementAndGet();
                        } finally {
                            active.decrementAndGet();
                            semaphore.release();
                        }
                    } catch (Exception e) {
                        restoreInterruptIfNeeded(e);
                        failure.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            await(done, "Timed out waiting for Semaphore tasks");
            rethrowIfFailed(failure, "Semaphore demo failed");
            return new SemaphoreRunResult(completedTasks.get(), maxConcurrent.get());
        } finally {
            shutdown(executor);
        }
    }

    private static void updateMax(AtomicInteger maxConcurrent, int current) {
        int previous;
        do {
            previous = maxConcurrent.get();
            if (current <= previous) {
                return;
            }
        } while (!maxConcurrent.compareAndSet(previous, current));
    }

    private static void restoreInterruptIfNeeded(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch, String timeoutMessage) throws InterruptedException {
        if (!latch.await(WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException(timeoutMessage);
        }
    }

    private static void rethrowIfFailed(AtomicReference<Exception> failure, String message) {
        Exception cause = failure.get();
        if (cause != null) {
            throw new IllegalStateException(message, cause);
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            executor.shutdownNow();
        }
    }

    private static void validatePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
    }

    public static final class PhasedRunResult {
        private final int arrivals;
        private final int completedPhases;

        public PhasedRunResult(int arrivals, int completedPhases) {
            this.arrivals = arrivals;
            this.completedPhases = completedPhases;
        }

        public int getArrivals() {
            return arrivals;
        }

        public int getCompletedPhases() {
            return completedPhases;
        }
    }

    public static final class SemaphoreRunResult {
        private final int completedTasks;
        private final int maxConcurrent;

        public SemaphoreRunResult(int completedTasks, int maxConcurrent) {
            this.completedTasks = completedTasks;
            this.maxConcurrent = maxConcurrent;
        }

        public int getCompletedTasks() {
            return completedTasks;
        }

        public int getMaxConcurrent() {
            return maxConcurrent;
        }
    }
}
