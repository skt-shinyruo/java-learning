package yier.bubu.base;

public final class Strings {
    private Strings() {
    }

    public static boolean isBlank(String value) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static String trimToEmpty(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }
}

