package yier.bubu.playground.livechat;

public final class ChatMessage {

    public enum Type {
        CHAT("chat"),
        SYSTEM("system"),
        CLOSED("closed");

        private final String wireName;

        Type(String wireName) {
            this.wireName = wireName;
        }

        static Type fromWireName(String wireName) {
            for (Type type : values()) {
                if (type.wireName.equals(wireName)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("unknown message type: " + wireName);
        }
    }

    private final Type type;
    private final String content;

    private ChatMessage(Type type, String content) {
        this.type = type;
        this.content = content;
    }

    public static ChatMessage chat(String content) {
        return new ChatMessage(Type.CHAT, content);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Type.SYSTEM, content);
    }

    public static ChatMessage closed(String content) {
        return new ChatMessage(Type.CLOSED, content);
    }

    public Type type() {
        return type;
    }

    public String content() {
        return content;
    }

    public String toJson() {
        return "{\"type\":\"" + type.wireName + "\",\"content\":\"" + escape(content) + "\"}";
    }

    public static ChatMessage parse(String json) {
        try {
            String type = null;
            String content = null;
            JsonReader reader = new JsonReader(json);
            reader.expect('{');
            while (!reader.tryConsume('}')) {
                String key = reader.readString();
                reader.expect(':');
                String value = reader.readString();
                if ("type".equals(key)) {
                    type = value;
                } else if ("content".equals(key)) {
                    content = value;
                }
                if (!reader.tryConsume(',')) {
                    reader.expect('}');
                    break;
                }
            }
            if (type == null || content == null) {
                return null;
            }
            return new ChatMessage(Type.fromWireName(type), content);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static final class JsonReader {
        private final String json;
        private int pos;

        JsonReader(String json) {
            this.json = json;
        }

        boolean tryConsume(char expected) {
            skipWhitespace();
            if (pos < json.length() && json.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        void expect(char expected) {
            if (!tryConsume(expected)) {
                throw new IllegalArgumentException("expected '" + expected + "' at position " + pos);
            }
        }

        String readString() {
            skipWhitespace();
            if (pos >= json.length() || json.charAt(pos) != '"') {
                throw new IllegalArgumentException("expected string at position " + pos);
            }
            pos++;
            StringBuilder out = new StringBuilder();
            while (pos < json.length()) {
                char c = json.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (pos >= json.length()) {
                        break;
                    }
                    char esc = json.charAt(pos++);
                    switch (esc) {
                        case '"', '\\', '/' -> out.append(esc);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            out.append((char) Integer.parseInt(json.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("bad escape: \\" + esc);
                    }
                } else {
                    out.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private void skipWhitespace() {
            while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
                pos++;
            }
        }
    }
}
