package yier.bubu.base.singleton;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * 基于字段更新器（AtomicReferenceFieldUpdater）的 CAS 懒加载单例。
 * <p>
 * 与 {@link AtomicReferenceSingleton} 相比，字段更新器更适合在“同一类的多个字段需要原子更新”的场景，
 * 这里仅作为一次性初始化的示例。
 */
public final class FieldUpdaterSingleton {
    private static final class Holder {
        volatile FieldUpdaterSingleton instance;
    }

    private static final Holder HOLDER = new Holder();

    private static final AtomicReferenceFieldUpdater<Holder, FieldUpdaterSingleton> UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(Holder.class, FieldUpdaterSingleton.class, "instance");

    private FieldUpdaterSingleton() {
    }

    public static FieldUpdaterSingleton getInstance() {
        FieldUpdaterSingleton current = HOLDER.instance;
        if (current != null) {
            return current;
        }

        FieldUpdaterSingleton created = new FieldUpdaterSingleton();
        if (UPDATER.compareAndSet(HOLDER, null, created)) {
            return created;
        }

        // 其它线程已经完成初始化；等待并返回最终值（避免返回 null）。
        while ((current = HOLDER.instance) == null) {
            Thread.yield();
        }
        return current;
    }
}
