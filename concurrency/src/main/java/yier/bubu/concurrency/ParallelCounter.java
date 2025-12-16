package yier.bubu.concurrency;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ParallelCounter {
    private ParallelCounter() {
    }

    public static int incrementInParallel(int increments, int threads) throws InterruptedException {
        if (increments < 0) {
            throw new IllegalArgumentException("increments must be >= 0");
        }
        if (threads <= 0) {
            throw new IllegalArgumentException("threads must be > 0");
        }

        AtomicInteger counter = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(increments);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < increments; i++) {
                executor.execute(() -> {
                    counter.incrementAndGet();
                    latch.countDown();
                });
            }

            boolean finished = latch.await(2, TimeUnit.SECONDS);
            if (!finished) {
                throw new IllegalStateException("Timed out waiting for tasks");
            }
            return counter.get();
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }
    }
}

