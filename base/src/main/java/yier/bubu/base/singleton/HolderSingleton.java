package yier.bubu.base.singleton;

/**
 * 静态内部类（Initialization-on-demand holder）单例。
 * <p>
 * 优点：懒加载、线程安全、无显式同步，通常是“类”形式单例的首选写法。
 */
public final class HolderSingleton {
    private HolderSingleton() {
    }

    public static HolderSingleton getInstance() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        private static final HolderSingleton INSTANCE = new HolderSingleton();
    }
}

