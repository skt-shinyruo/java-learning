package yier.bubu.base.singleton;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 通用 Lazy / 记忆化 Supplier：把“一次性初始化”抽成可复用组件（单例只是其特例）。
 * <p>
 * 特性：
 * <ul>
 *   <li>线程安全</li>
 *   <li>初始化完成后无同步开销（快速路径仅 volatile 读）</li>
 *   <li>允许初始化值为 null（用哨兵区分未初始化状态）</li>
 * </ul>
 */
public final class Lazy<T> implements Supplier<T> {
    private static final Object UNINITIALIZED = new Object();

    private final Object lock = new Object();

    private volatile Object value = UNINITIALIZED;
    private Supplier<? extends T> initializer;

    private Lazy(Supplier<? extends T> initializer) {
        this.initializer = Objects.requireNonNull(initializer, "initializer");
    }

    public static <T> Lazy<T> of(Supplier<? extends T> initializer) {
        return new Lazy<T>(initializer);
    }

    @Override
    public T get() {
        Object current = value;
        if (current != UNINITIALIZED) {
            @SuppressWarnings("unchecked")
            T typed = (T) current;
            return typed;
        }

        synchronized (lock) {
            current = value;
            if (current == UNINITIALIZED) {
                T created = initializer.get();
                value = created;
                initializer = null;
                return created;
            }
        }

        @SuppressWarnings("unchecked")
        T typed = (T) value;
        return typed;
    }
}

