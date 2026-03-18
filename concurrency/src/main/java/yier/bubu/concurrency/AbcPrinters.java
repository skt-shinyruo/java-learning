package yier.bubu.concurrency;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 三个线程严格按顺序循环打印 ABC 的多种实现方式（偏“学习版”，尽量短）。
 */
public final class AbcPrinters {
    private AbcPrinters() {
    }

    public static void main(String[] args) throws Exception {
        int rounds = 10;

        System.out.println("Semaphore:      " + printBySemaphore(rounds));
        System.out.println("Condition:      " + printByCondition(rounds));
        System.out.println("wait/notifyAll: " + printByWaitNotifyAll(rounds));
        System.out.println("BlockingQueue:  " + printByBlockingQueue(rounds));
        System.out.println("LockSupport:    " + printByLockSupport(rounds));
    }

    /**
     * 方式 1：Semaphore 令牌轮转（推荐，代码最简洁）。
     */
    public static String printBySemaphore(int rounds) throws InterruptedException {
        validateRounds(rounds);
        StringBuilder out = new StringBuilder(rounds * 3);

        Semaphore sa = new Semaphore(1);
        Semaphore sb = new Semaphore(0);
        Semaphore sc = new Semaphore(0);

        Thread ta = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                sa.acquireUninterruptibly();
                out.append('A');
                sb.release();
            }
        });

        Thread tb = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                sb.acquireUninterruptibly();
                out.append('B');
                sc.release();
            }
        });

        Thread tc = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                sc.acquireUninterruptibly();
                out.append('C');
                sa.release();
            }
        });

        startAndJoin(ta, tb, tc);
        return out.toString();
    }

    /**
     * 方式 2：ReentrantLock + Condition（显式等待队列）。
     */
    public static String printByCondition(int rounds) throws InterruptedException {
        validateRounds(rounds);
        StringBuilder out = new StringBuilder(rounds * 3);

        ReentrantLock lock = new ReentrantLock();
        Condition ca = lock.newCondition();
        Condition cb = lock.newCondition();
        Condition cc = lock.newCondition();

        AtomicInteger turn = new AtomicInteger(0); // 0:A 1:B 2:C

        Thread ta = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                lock.lock();
                try {
                    while (turn.get() % 3 != 0) {
                        ca.awaitUninterruptibly();
                    }
                    out.append('A');
                    turn.incrementAndGet();
                    cb.signal();
                } finally {
                    lock.unlock();
                }
            }
        });

        Thread tb = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                lock.lock();
                try {
                    while (turn.get() % 3 != 1) {
                        cb.awaitUninterruptibly();
                    }
                    out.append('B');
                    turn.incrementAndGet();
                    cc.signal();
                } finally {
                    lock.unlock();
                }
            }
        });

        Thread tc = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                lock.lock();
                try {
                    while (turn.get() % 3 != 2) {
                        cc.awaitUninterruptibly();
                    }
                    out.append('C');
                    turn.incrementAndGet();
                    ca.signal();
                } finally {
                    lock.unlock();
                }
            }
        });

        startAndJoin(ta, tb, tc);
        return out.toString();
    }

    /**
     * 方式 3：synchronized + wait/notifyAll（最基础的监视器用法）。
     */
    public static String printByWaitNotifyAll(int rounds) throws InterruptedException {
        validateRounds(rounds);
        StringBuilder out = new StringBuilder(rounds * 3);

        Object lock = new Object();
        AtomicInteger turn = new AtomicInteger(0); // 0:A 1:B 2:C

        Thread ta = new Thread(() -> {
            try {
                for (int i = 0; i < rounds; i++) {
                    synchronized (lock) {
                        while (turn.get() % 3 != 0) {
                            lock.wait();
                        }
                        out.append('A');
                        turn.incrementAndGet();
                        lock.notifyAll();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread tb = new Thread(() -> {
            try {
                for (int i = 0; i < rounds; i++) {
                    synchronized (lock) {
                        while (turn.get() % 3 != 1) {
                            lock.wait();
                        }
                        out.append('B');
                        turn.incrementAndGet();
                        lock.notifyAll();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread tc = new Thread(() -> {
            try {
                for (int i = 0; i < rounds; i++) {
                    synchronized (lock) {
                        while (turn.get() % 3 != 2) {
                            lock.wait();
                        }
                        out.append('C');
                        turn.incrementAndGet();
                        lock.notifyAll();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        startAndJoin(ta, tb, tc);
        return out.toString();
    }

    /**
     * 方式 4：BlockingQueue 令牌传递（接力棒模型）。
     */
    public static String printByBlockingQueue(int rounds) throws InterruptedException {
        validateRounds(rounds);
        StringBuilder out = new StringBuilder(rounds * 3);

        BlockingQueue<Integer> qa = new ArrayBlockingQueue<>(1);
        BlockingQueue<Integer> qb = new ArrayBlockingQueue<>(1);
        BlockingQueue<Integer> qc = new ArrayBlockingQueue<>(1);

        qa.put(1); // 先给 A 令牌，启动链条

        Thread ta = new Thread(() -> {
            try {
                for (int i = 0; i < rounds; i++) {
                    qa.take();
                    try {
                        out.append('A');
                    } finally {
                        qb.put(1);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread tb = new Thread(() -> {
            try {
                for (int i = 0; i < rounds; i++) {
                    qb.take();
                    try {
                        out.append('B');
                    } finally {
                        qc.put(1);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread tc = new Thread(() -> {
            try {
                for (int i = 0; i < rounds; i++) {
                    qc.take();
                    try {
                        out.append('C');
                    } finally {
                        qa.put(1);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        startAndJoin(ta, tb, tc);
        return out.toString();
    }

    /**
     * 方式 5：LockSupport park/unpark（更底层的阻塞/唤醒）。
     *
     * <p>注意：park 可能“无理由返回”，因此需要 turn 校验 + while 循环。
     */
    public static String printByLockSupport(int rounds) throws InterruptedException {
        validateRounds(rounds);
        StringBuilder out = new StringBuilder(rounds * 3);

        AtomicInteger turn = new AtomicInteger(0); // 0:A 1:B 2:C
        Thread[] threads = new Thread[3];

        threads[0] = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                while (turn.get() != 0) {
                    LockSupport.park();
                }
                out.append('A');
                turn.set(1);
                LockSupport.unpark(threads[1]);
            }
        });

        threads[1] = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                while (turn.get() != 1) {
                    LockSupport.park();
                }
                out.append('B');
                turn.set(2);
                LockSupport.unpark(threads[2]);
            }
        });

        threads[2] = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                while (turn.get() != 2) {
                    LockSupport.park();
                }
                out.append('C');
                turn.set(0);
                LockSupport.unpark(threads[0]);
            }
        });

        threads[0].start();
        threads[1].start();
        threads[2].start();

        LockSupport.unpark(threads[0]); // 启动 A

        threads[0].join();
        threads[1].join();
        threads[2].join();

        return out.toString();
    }

    private static void startAndJoin(Thread ta, Thread tb, Thread tc) throws InterruptedException {
        ta.start();
        tb.start();
        tc.start();
        ta.join();
        tb.join();
        tc.join();
    }

    private static void validateRounds(int rounds) {
        if (rounds < 0) {
            throw new IllegalArgumentException("rounds must be >= 0");
        }
    }
}
