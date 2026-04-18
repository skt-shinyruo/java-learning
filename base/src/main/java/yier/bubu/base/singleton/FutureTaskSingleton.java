package yier.bubu.base.singleton;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FutureTask / SingleFlight 风格的懒加载单例。
 * <p>
 * 首次触发创建并启动任务，其它线程等待同一个 FutureTask 的结果。
 */
public final class FutureTaskSingleton {
    private static final AtomicReference<FutureTask<FutureTaskSingleton>> TASK =
            new AtomicReference<FutureTask<FutureTaskSingleton>>();

    private FutureTaskSingleton() {
    }

    public static FutureTaskSingleton getInstance() {
        FutureTask<FutureTaskSingleton> task = TASK.get();
        if (task == null) {
            FutureTask<FutureTaskSingleton> created = new FutureTask<FutureTaskSingleton>(new Callable<FutureTaskSingleton>() {
                @Override
                public FutureTaskSingleton call() {
                    return new FutureTaskSingleton();
                }
            });

            if (TASK.compareAndSet(null, created)) {
                task = created;
                task.run();
            } else {
                task = TASK.get();
            }
        }

        try {
            return task.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for singleton initialization", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            throw new IllegalStateException("Singleton initialization failed", cause == null ? exception : cause);
        }
    }
}

