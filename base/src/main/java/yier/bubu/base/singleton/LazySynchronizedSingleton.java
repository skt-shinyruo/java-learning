package yier.bubu.base.singleton;

/**
 * 懒汉式单例（同步方法）。
 * <p>
 * 优点：懒加载、线程安全、实现直观。
 * 缺点：每次获取实例都需要同步，性能一般（通常不推荐作为默认方案）。
 */
public final class LazySynchronizedSingleton {
    private static LazySynchronizedSingleton instance;

    private LazySynchronizedSingleton() {
    }

    public static synchronized LazySynchronizedSingleton getInstance() {
        if (instance == null) {
            instance = new LazySynchronizedSingleton();
        }
        return instance;
    }
}

