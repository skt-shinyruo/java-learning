package yier.bubu.jvm;

import java.io.Closeable;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

@RuntimeMark("class")
public class ClassFileTour<T extends Number> implements Runnable, Closeable {
    public static final int MAGIC = 7;
    private static final String PREFIX = "score:";
    private static int created;

    @RuntimeMark("field")
    private @TypeMark("generic-field") T value;
    private final List<String> history = new ArrayList<String>();

    static {
        created = MAGIC;
    }

    public ClassFileTour(T value) {
        this.value = value;
        this.history.add(PREFIX + value);
    }

    @RuntimeMark("method")
    public int compute(int base, String... tags) throws IOException {
        int total = base + value.intValue();
        for (String tag : tags) {
            total += tag.length();
        }

        final int snapshot = total;

        IntSupplier task = () -> snapshot + history.size();

        Runnable printer = new Runnable() {
            @Override
            public void run() {
                System.out.println(PREFIX + value);
            }
        };

        class LocalFormatter {
            private final int number;

            LocalFormatter(int number) {
                this.number = number;
            }

            String format() {
                return PREFIX + number;
            }
        }

        history.add(new LocalFormatter(total).format());
        printer.run();
        return task.getAsInt();
    }

    public int guardedLength(String text) {
        try {
            return text.length();
        } catch (NullPointerException e) {
            return -1;
        } finally {
            history.add("guarded");
        }
    }

    public static ClassFileTour<Integer> of(int value) {
        return new ClassFileTour<Integer>(value);
    }

    @Override
    public void run() {
        history.add("run");
    }

    @Override
    public void close() throws IOException {
        history.clear();
    }
}

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.TYPE_USE})
@interface RuntimeMark {
    String value();

    int level() default 1;
}

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
@interface TypeMark {
    String value();
}
