package yier.bubu.base;

import java.util.Objects;

public final class HelloBase {
    public String greet(String name) {
        String normalized = Strings.trimToEmpty(Objects.requireNonNull(name, "name"));
        if (normalized.isEmpty()) {
            return "Hello, anonymous";
        }
        return "Hello, " + normalized;
    }
}

