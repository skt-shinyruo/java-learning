package yier.bubu.base.singleton;

import java.util.concurrent.atomic.AtomicReference;

/**
 * CAS（AtomicReference）实现的懒加载单例。
 * <p>
 * 优点：无需显式锁，线程安全。
 * 缺点：并发首次初始化时可能会创建多个临时对象（最终只会保留并返回同一个实例）。
 */
public final class AtomicReferenceSingleton {
    private static final AtomicReference<AtomicReferenceSingleton> INSTANCE =
            new AtomicReference<AtomicReferenceSingleton>();

    private AtomicReferenceSingleton() {
    }

    public static AtomicReferenceSingleton getInstance() {
        AtomicReferenceSingleton current = INSTANCE.get();
        if (current != null) {
            return current;
        }

        AtomicReferenceSingleton created = new AtomicReferenceSingleton();
        if (INSTANCE.compareAndSet(null, created)) {
            return created;
        }
        return INSTANCE.get();
    }
}

