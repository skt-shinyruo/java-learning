package yier.bubu.jvm;

import java.util.Arrays;

public final class JvmMemoryApp {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printHelp();
            return;
        }

        String cmd = args[0].toLowerCase();
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        switch (cmd) {
            case "help":
            case "-h":
            case "--help":
                printHelp();
                return;
            case "inspect":
                MemoryInspector.printAll();
                return;
            case "direct":
                DirectMemoryDemo.run(rest);
                return;
            case "metaspace":
                MetaspaceDemo.run(rest);
                return;
            case "stack":
                StackOverflowDemo.run(rest);
                return;
            default:
                System.out.println("Unknown command: " + cmd);
                printHelp();
        }
    }

    private static void printHelp() {
        System.out.println("Usage:");
        System.out.println("  java -cp jvm/target/classes yier.bubu.jvm.JvmMemoryApp <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  inspect                   Print JVM/memory/thread/classloading summary");
        System.out.println("  direct   [--mb N]         Allocate DirectByteBuffer to observe direct memory");
        System.out.println("  metaspace[--count N]      Define many classes to observe metaspace growth (Java 8+)");
        System.out.println("  stack                     Trigger StackOverflowError to observe -Xss effect");
        System.out.println("  help                      Show this help");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  mvn -pl jvm -am -DskipTests package");
        System.out.println("  java -cp jvm/target/classes yier.bubu.jvm.JvmMemoryApp inspect");
        System.out.println("  java -XX:MaxDirectMemorySize=64m -cp jvm/target/classes yier.bubu.jvm.JvmMemoryApp direct --mb 96 --chunkMb 4 --touch true");
        System.out.println("  java -XX:MaxMetaspaceSize=64m -cp jvm/target/classes yier.bubu.jvm.JvmMemoryApp metaspace --count 20000");
        System.out.println("  java -Xss256k -cp jvm/target/classes yier.bubu.jvm.JvmMemoryApp stack");
    }
}
