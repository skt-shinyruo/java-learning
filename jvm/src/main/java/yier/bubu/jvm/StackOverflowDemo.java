package yier.bubu.jvm;

final class StackOverflowDemo {
    private StackOverflowDemo() {
    }

    private static int maxDepth;

    static void run(String[] args) {
        maxDepth = 0;
        System.out.println("[StackOverflowDemo]");
        System.out.println("Tip: try different -Xss values, e.g. -Xss256k / -Xss1m / -Xss2m");
        System.out.println();
        try {
            recurse(1);
        } catch (StackOverflowError e) {
            System.out.println("StackOverflowError after depth ~= " + maxDepth);
        }
    }

    private static void recurse(int depth) {
        maxDepth = depth;
        recurse(depth + 1);
    }
}

