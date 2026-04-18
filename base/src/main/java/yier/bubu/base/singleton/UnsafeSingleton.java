package yier.bubu.base.singleton;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * 基于 sun.misc.Unsafe 的 CAS 懒加载单例（内部 API，不推荐在生产中使用）。
 * <p>
 * 这里演示 {@link Unsafe#compareAndSwapObject(Object, long, Object, Object)} 的用法。
 */
public final class UnsafeSingleton {
    private static volatile UnsafeSingleton instance;

    private static final Unsafe UNSAFE;
    private static final Object INSTANCE_BASE;
    private static final long INSTANCE_OFFSET;

    static {
        try {
            Field theUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            UNSAFE = (Unsafe) theUnsafeField.get(null);

            Field instanceField = UnsafeSingleton.class.getDeclaredField("instance");
            INSTANCE_BASE = UNSAFE.staticFieldBase(instanceField);
            INSTANCE_OFFSET = UNSAFE.staticFieldOffset(instanceField);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private UnsafeSingleton() {
    }

    public static UnsafeSingleton getInstance() {
        UnsafeSingleton current = instance;
        if (current != null) {
            return current;
        }

        UnsafeSingleton created = new UnsafeSingleton();
        if (UNSAFE.compareAndSwapObject(INSTANCE_BASE, INSTANCE_OFFSET, null, created)) {
            return created;
        }

        while ((current = instance) == null) {
            Thread.yield();
        }
        return current;
    }
}

