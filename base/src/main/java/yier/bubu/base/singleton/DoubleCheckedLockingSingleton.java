package yier.bubu.base.singleton;

/**
 * 双重检查锁（DCL）单例。
 * <p>
 * 要点：instance 必须是 volatile，防止指令重排导致“半初始化对象”被其它线程看到。
 * 在 Java 5+（含 Java 8）内存模型下，这种写法是正确且常见的。
 */
public final class DoubleCheckedLockingSingleton {
    private static volatile DoubleCheckedLockingSingleton instance;

    private DoubleCheckedLockingSingleton() {
    }

    public static DoubleCheckedLockingSingleton getInstance() {
        if (instance == null) {
            synchronized (DoubleCheckedLockingSingleton.class) {
                if (instance == null) {
                    instance = new DoubleCheckedLockingSingleton();
                }
            }
        }
        return instance;
    }
}

