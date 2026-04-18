package yier.bubu.base.singleton;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * 基于 VarHandle 的 CAS 懒加载单例（JDK 9+）。
 * <p>
 * 这里用 {@link VarHandle#compareAndExchange(Object...)} 完成一次性初始化；
 * 同样也可以用 {@link VarHandle#compareAndSet(Object...)} 实现。
 */
public final class VarHandleSingleton {
    private static volatile VarHandleSingleton instance;

    private static final VarHandle INSTANCE_HANDLE;

    static {
        try {
            INSTANCE_HANDLE =
                    MethodHandles.lookup().findStaticVarHandle(
                            VarHandleSingleton.class,
                            "instance",
                            VarHandleSingleton.class);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private VarHandleSingleton() {
    }

    public static VarHandleSingleton getInstance() {
        VarHandleSingleton current = instance;
        if (current != null) {
            return current;
        }

        VarHandleSingleton created = new VarHandleSingleton();
        VarHandleSingleton witness = (VarHandleSingleton) INSTANCE_HANDLE.compareAndExchange(null, created);
        return witness == null ? created : witness;
    }
}

