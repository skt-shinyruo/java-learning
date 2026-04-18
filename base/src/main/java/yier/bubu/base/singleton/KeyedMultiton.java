package yier.bubu.base.singleton;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Multiton：不是“全局唯一”的单例，而是“同一个 key 对应一个实例”。
 * <p>
 * 注意：这个简单实现会持有 key 的强引用，示例不处理实例淘汰/生命周期管理。
 */
public final class KeyedMultiton {
    private static final ConcurrentMap<String, KeyedMultiton> INSTANCES =
            new ConcurrentHashMap<String, KeyedMultiton>();

    private final String key;

    private KeyedMultiton(String key) {
        this.key = key;
    }

    public static KeyedMultiton getInstance(String key) {
        String normalizedKey = Objects.requireNonNull(key, "key");
        return INSTANCES.computeIfAbsent(normalizedKey, KeyedMultiton::new);
    }

    public String getKey() {
        return key;
    }
}

