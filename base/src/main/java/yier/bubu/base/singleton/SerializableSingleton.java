package yier.bubu.base.singleton;

import java.io.ObjectStreamException;
import java.io.Serializable;

/**
 * 可序列化的单例。
 * <p>
 * 注意：如果不提供 readResolve，反序列化会生成一个全新的对象，从而破坏单例语义。
 * 对于强安全需求的场景，优先考虑 {@link EnumSingleton}。
 */
public final class SerializableSingleton implements Serializable {
    private static final long serialVersionUID = 1L;

    private static final SerializableSingleton INSTANCE = new SerializableSingleton();

    private SerializableSingleton() {
    }

    public static SerializableSingleton getInstance() {
        return INSTANCE;
    }

    private Object readResolve() throws ObjectStreamException {
        return INSTANCE;
    }
}

