package yier.bubu.jvm;

import java.util.ArrayList;
import java.util.List;

final class MetaspaceDemo {
    private MetaspaceDemo() {
    }

    static void run(String[] args) throws Exception {
        int count = CliArgs.getInt(args, "--count", 2000);
        int reportEvery = CliArgs.getInt(args, "--reportEvery", 200);
        int sleepSeconds = CliArgs.getInt(args, "--sleepSeconds", 0);

        if (count <= 0) {
            System.out.println("Invalid args: --count must be > 0");
            return;
        }

        System.out.println("[MetaspaceDemo]");
        System.out.println("Defining classes to grow Metaspace. count=" + count + " reportEvery=" + reportEvery);
        System.out.println("Tip: set a small limit to observe OOM quickly: -XX:MaxMetaspaceSize=64m");
        System.out.println();

        GeneratedClassLoader loader = new GeneratedClassLoader(MetaspaceDemo.class.getClassLoader());
        List<Class<?>> defined = new ArrayList<>(Math.min(count, 4096));

        for (int i = 0; i < count; i++) {
            String className = "yier.bubu.jvm.generated.Gen" + i;
            byte[] bytes = MinimalClassFile.emptyClass(className);
            Class<?> c = loader.define(className, bytes);
            defined.add(c);

            if ((i + 1) % reportEvery == 0 || i == count - 1) {
                System.out.println("definedClasses=" + (i + 1));
                MemoryInspector.printMetaspaceLikePools();
                System.out.println();
            }
        }

        System.out.println("Holding references to prevent class unloading. defined.size=" + defined.size());
        if (sleepSeconds > 0) {
            System.out.println("Sleeping " + sleepSeconds + "s (attach tools like jcmd/jconsole if you want).");
            Thread.sleep(sleepSeconds * 1000L);
        }
    }

    static final class GeneratedClassLoader extends ClassLoader {
        GeneratedClassLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}

