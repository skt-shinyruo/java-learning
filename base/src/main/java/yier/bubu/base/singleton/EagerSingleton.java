package yier.bubu.base.singleton;

/**
 * 饿汉式单例：类加载时初始化实例。
 * <p>
 * 优点：实现简单、天然线程安全（依赖 JVM 的类初始化语义）。
 * 缺点：即使从未使用也会创建实例（不够懒加载）。
 */
public final class EagerSingleton {
    private static final EagerSingleton INSTANCE = new EagerSingleton();

    private EagerSingleton() {
    }

    public static EagerSingleton getInstance() {
        return INSTANCE;
    }
}

