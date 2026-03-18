package yier.bubu.jvm;

final class CliArgs {
    private CliArgs() {
    }

    static String get(String[] args, String key, String defaultValue) {
        String asPrefix = key + "=";
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals(key) && i + 1 < args.length) {
                return args[i + 1];
            }
            if (a.startsWith(asPrefix)) {
                return a.substring(asPrefix.length());
            }
        }
        return defaultValue;
    }

    static int getInt(String[] args, String key, int defaultValue) {
        String v = get(args, key, null);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    static long getLong(String[] args, String key, long defaultValue) {
        String v = get(args, key, null);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    static boolean getBoolean(String[] args, String key, boolean defaultValue) {
        String v = get(args, key, null);
        if (v == null) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(v)
                || "1".equals(v)
                || "yes".equalsIgnoreCase(v)
                || "y".equalsIgnoreCase(v);
    }
}

