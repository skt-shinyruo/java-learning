package yier.bubu.base.singleton;

/**
 * 静态代码块初始化的饿汉式单例。
 * <p>
 * 与 {@link EagerSingleton} 类似：类加载时完成初始化。
 * 这种写法的价值主要在于：如果初始化过程需要捕获并包装异常，可以在 static 块里处理。
 */
public final class StaticBlockSingleton {
    private static final StaticBlockSingleton INSTANCE;

    static {
        INSTANCE = new StaticBlockSingleton();
    }

    private StaticBlockSingleton() {
    }

    public static StaticBlockSingleton getInstance() {
        return INSTANCE;
    }
}

