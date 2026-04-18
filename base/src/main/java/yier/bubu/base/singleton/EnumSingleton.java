package yier.bubu.base.singleton;

/**
 * 枚举单例（推荐）。
 * <p>
 * 优点：天然防止反射/反序列化破坏单例语义，写法极简。
 */
public enum EnumSingleton {
    INSTANCE;

    public static EnumSingleton getInstance() {
        return INSTANCE;
    }
}

