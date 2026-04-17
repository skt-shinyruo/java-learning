package yier.bubu.concurrency.jmm;

import java.lang.management.ManagementFactory;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 独立演示“普通 stop flag / volatile stop flag / -Xint 观察现象”的示例。
 *
 * <p>说明：</p>
 * <ul>
 *   <li>{@link Mode#PLAIN} 只有普通 boolean，没有 happens-before 保证。</li>
 *   <li>{@link Mode#VOLATILE} 用 volatile 建立可见性保证。</li>
 *   <li>{@code -Xint} 只是关闭 JIT 的运行方式，不是同步手段。</li>
 * </ul>
 */
public final class XintStopFlagDemo {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private XintStopFlagDemo() {
    }

    public static void main(String[] args) throws Exception {
        Mode mode = args.length > 0 ? Mode.fromCli(args[0]) : Mode.PLAIN;
        long stopDelayMillis = args.length > 1 ? Long.parseLong(args[1]) : 1000L;
        long joinTimeoutMillis = args.length > 2 ? Long.parseLong(args[2]) : 1000L;

        System.out.println("start " + now());
        ExperimentResult result = runExperiment(mode, stopDelayMillis, joinTimeoutMillis, true);
        System.out.println("result " + result.toSummaryLine());
        System.out.println("end " + now());
    }

    public static ExperimentResult runExperiment(
            Mode mode,
            long stopDelayMillis,
            long joinTimeoutMillis,
            boolean printTimeline) throws InterruptedException {

        if (stopDelayMillis < 0 || joinTimeoutMillis < 0) {
            throw new IllegalArgumentException("stopDelayMillis and joinTimeoutMillis must be >= 0");
        }

        StopFlagWorker worker = mode == Mode.VOLATILE
                ? new VolatileStopFlagWorker()
                : new PlainStopFlagWorker();

        Thread thread = new Thread(worker, mode.cliName() + "-stop-flag-worker");
        // 反例模式可能故意演示“停不下来”，这里设为 daemon，避免手工实验或说明性测试卡死整个 JVM。
        thread.setDaemon(true);

        long startedAt = System.nanoTime();
        thread.start();

        if (!worker.awaitStarted(1, TimeUnit.SECONDS)) {
            throw new IllegalStateException("worker did not start in time");
        }

        if (printTimeline) {
            System.out.println("stop-request-at " + now());
        }

        if (stopDelayMillis > 0) {
            Thread.sleep(stopDelayMillis);
        }
        worker.requestStop();

        thread.join(joinTimeoutMillis);

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        boolean workerAliveAfterJoin = thread.isAlive();

        return new ExperimentResult(
                mode,
                isInterpreterOnly(),
                stopDelayMillis,
                joinTimeoutMillis,
                !workerAliveAfterJoin,
                workerAliveAfterJoin,
                worker.iterations(),
                elapsedMillis);
    }

    public static boolean isInterpreterOnly() {
        List<String> inputArguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        return inputArguments.contains("-Xint");
    }

    private static String now() {
        return LocalTime.now().format(TIME_FORMAT);
    }

    public enum Mode {
        PLAIN("plain", false),
        VOLATILE("volatile", true);

        private final String cliName;
        private final boolean happensBeforeGuarantee;

        Mode(String cliName, boolean happensBeforeGuarantee) {
            this.cliName = cliName;
            this.happensBeforeGuarantee = happensBeforeGuarantee;
        }

        public String cliName() {
            return cliName;
        }

        public boolean hasHappensBeforeGuarantee() {
            return happensBeforeGuarantee;
        }

        public static Mode fromCli(String value) {
            if ("plain".equalsIgnoreCase(value)) {
                return PLAIN;
            }
            if ("volatile".equalsIgnoreCase(value)) {
                return VOLATILE;
            }
            throw new IllegalArgumentException("Unsupported mode: " + value + ". Use plain or volatile.");
        }
    }

    public static final class ExperimentResult {
        private final Mode mode;
        private final boolean interpreterOnly;
        private final long stopDelayMillis;
        private final long joinTimeoutMillis;
        private final boolean stoppedWithinTimeout;
        private final boolean workerAliveAfterJoin;
        private final long iterations;
        private final long elapsedMillis;

        private ExperimentResult(
                Mode mode,
                boolean interpreterOnly,
                long stopDelayMillis,
                long joinTimeoutMillis,
                boolean stoppedWithinTimeout,
                boolean workerAliveAfterJoin,
                long iterations,
                long elapsedMillis) {
            this.mode = mode;
            this.interpreterOnly = interpreterOnly;
            this.stopDelayMillis = stopDelayMillis;
            this.joinTimeoutMillis = joinTimeoutMillis;
            this.stoppedWithinTimeout = stoppedWithinTimeout;
            this.workerAliveAfterJoin = workerAliveAfterJoin;
            this.iterations = iterations;
            this.elapsedMillis = elapsedMillis;
        }

        public Mode mode() {
            return mode;
        }

        public boolean interpreterOnly() {
            return interpreterOnly;
        }

        public long stopDelayMillis() {
            return stopDelayMillis;
        }

        public long joinTimeoutMillis() {
            return joinTimeoutMillis;
        }

        public boolean stoppedWithinTimeout() {
            return stoppedWithinTimeout;
        }

        public boolean workerAliveAfterJoin() {
            return workerAliveAfterJoin;
        }

        public long iterations() {
            return iterations;
        }

        public long elapsedMillis() {
            return elapsedMillis;
        }

        public boolean hasHappensBeforeGuarantee() {
            return mode.hasHappensBeforeGuarantee();
        }

        public String toSummaryLine() {
            return "mode=" + mode.cliName()
                    + " interpreterOnly=" + interpreterOnly
                    + " happensBeforeGuarantee=" + hasHappensBeforeGuarantee()
                    + " stopDelayMillis=" + stopDelayMillis
                    + " joinTimeoutMillis=" + joinTimeoutMillis
                    + " stoppedWithinTimeout=" + stoppedWithinTimeout
                    + " workerAliveAfterJoin=" + workerAliveAfterJoin
                    + " iterations=" + iterations
                    + " elapsedMillis=" + elapsedMillis;
        }
    }

    private interface StopFlagWorker extends Runnable {
        boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException;

        void requestStop();

        long iterations();
    }

    private abstract static class AbstractStopFlagWorker implements StopFlagWorker {
        private final CountDownLatch started = new CountDownLatch(1);
        private long iterations;

        @Override
        public final void run() {
            started.countDown();
            long local = 0L;
            while (keepRunning()) {
                // 保持一点真实工作量，避免演示被“空循环”这个次要因素掩盖。
                local = (local * 1664525L) + 1013904223L;
            }
            iterations = positiveIterations(local);
        }

        @Override
        public final boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
            return started.await(timeout, unit);
        }

        @Override
        public final long iterations() {
            return iterations;
        }

        private static long positiveIterations(long value) {
            if (value == 0L) {
                return 1L;
            }
            if (value == Long.MIN_VALUE) {
                return Long.MAX_VALUE;
            }
            return Math.abs(value);
        }

        protected abstract boolean keepRunning();
    }

    private static final class PlainStopFlagWorker extends AbstractStopFlagWorker {
        private boolean running = true;

        @Override
        public void requestStop() {
            running = false;
        }

        @Override
        protected boolean keepRunning() {
            return running;
        }
    }

    private static final class VolatileStopFlagWorker extends AbstractStopFlagWorker {
        private volatile boolean running = true;

        @Override
        public void requestStop() {
            running = false;
        }

        @Override
        protected boolean keepRunning() {
            return running;
        }
    }
}
