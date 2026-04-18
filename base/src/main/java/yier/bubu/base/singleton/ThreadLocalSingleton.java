package yier.bubu.base.singleton;

/**
 * 线程内单例（每个线程拥有自己的“单例”实例）。
 * <p>
 * 适用：线程上下文隔离（例如非线程安全对象的线程内复用）。
 * 注意：这不是“全局唯一实例”的单例语义，不同线程会得到不同对象。
 */
public final class ThreadLocalSingleton {
    private static final ThreadLocal<ThreadLocalSingleton> THREAD_LOCAL =
            new ThreadLocal<ThreadLocalSingleton>() {
                @Override
                protected ThreadLocalSingleton initialValue() {
                    return new ThreadLocalSingleton();
                }
            };

    private ThreadLocalSingleton() {
    }

    public static ThreadLocalSingleton getInstance() {
        return THREAD_LOCAL.get();
    }

    public static void remove() {
        THREAD_LOCAL.remove();
    }
}

