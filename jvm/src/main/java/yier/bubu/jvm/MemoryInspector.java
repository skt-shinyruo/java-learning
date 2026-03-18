package yier.bubu.jvm;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Locale;

final class MemoryInspector {
    private MemoryInspector() {
    }

    static void printAll() {
        printJvmInfo();
        System.out.println();
        printMemorySummary();
        System.out.println();
        printMemoryPools();
        System.out.println();
        printBufferPools();
        System.out.println();
        printThreadsAndClasses();
    }

    static void printJvmInfo() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        System.out.println("[JVM]");
        System.out.println("pid=" + pidOrUnknown(runtime.getName()));
        System.out.println("java.version=" + System.getProperty("java.version"));
        System.out.println("java.vendor=" + System.getProperty("java.vendor"));
        System.out.println("java.vm.name=" + System.getProperty("java.vm.name"));
        System.out.println("java.vm.version=" + System.getProperty("java.vm.version"));
        System.out.println("os.name=" + System.getProperty("os.name"));
        System.out.println("os.arch=" + System.getProperty("os.arch"));
        System.out.println("availableProcessors=" + Runtime.getRuntime().availableProcessors());
        System.out.println("inputArguments=" + runtime.getInputArguments());
    }

    static void printMemorySummary() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        System.out.println("[Memory Summary]");
        printUsage("heap", memory.getHeapMemoryUsage());
        printUsage("nonHeap", memory.getNonHeapMemoryUsage());
    }

    static void printMemoryPools() {
        System.out.println("[Memory Pools]");
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage usage = pool.getUsage();
            System.out.println(pool.getName() + " (" + pool.getType() + ")");
            if (usage == null) {
                System.out.println("  usage=n/a");
                continue;
            }
            System.out.println("  used=" + formatBytes(usage.getUsed()) + " committed=" + formatBytes(usage.getCommitted()) + " max=" + formatBytes(usage.getMax()));
        }
    }

    static void printBufferPools() {
        System.out.println("[Buffer Pools]");
        List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
        if (pools.isEmpty()) {
            System.out.println("  (no BufferPoolMXBean available)");
            return;
        }
        for (BufferPoolMXBean pool : pools) {
            System.out.println(pool.getName()
                    + " count=" + pool.getCount()
                    + " used=" + formatBytes(pool.getMemoryUsed())
                    + " totalCapacity=" + formatBytes(pool.getTotalCapacity()));
        }
    }

    static void printThreadsAndClasses() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
        System.out.println("[Threads & ClassLoading]");
        System.out.println("threads.current=" + threads.getThreadCount() + " peak=" + threads.getPeakThreadCount() + " daemon=" + threads.getDaemonThreadCount());
        System.out.println("classes.loaded.current=" + classes.getLoadedClassCount()
                + " totalLoaded=" + classes.getTotalLoadedClassCount()
                + " unloaded=" + classes.getUnloadedClassCount());
    }

    static void printMetaspaceLikePools() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            String name = pool.getName();
            if (!"Metaspace".equals(name) && !"Compressed Class Space".equals(name)) {
                continue;
            }
            MemoryUsage usage = pool.getUsage();
            if (usage == null) {
                continue;
            }
            System.out.println(name + ": used=" + formatBytes(usage.getUsed()) + " committed=" + formatBytes(usage.getCommitted()) + " max=" + formatBytes(usage.getMax()));
        }
    }

    static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "n/a";
        }
        double mib = bytes / 1024d / 1024d;
        return String.format(Locale.ROOT, "%.2f MiB", mib);
    }

    private static void printUsage(String label, MemoryUsage usage) {
        if (usage == null) {
            System.out.println(label + ": n/a");
            return;
        }
        System.out.println(label
                + ": used=" + formatBytes(usage.getUsed())
                + " committed=" + formatBytes(usage.getCommitted())
                + " max=" + formatBytes(usage.getMax()));
    }

    private static String pidOrUnknown(String runtimeName) {
        if (runtimeName == null) {
            return "unknown";
        }
        int at = runtimeName.indexOf('@');
        if (at <= 0) {
            return "unknown";
        }
        return runtimeName.substring(0, at);
    }
}

