package co.wethinkcode.logisticsconnect;

import java.util.Map;

/** Small JSON encoder used by this independent Maven module. */
public final class Json {

    private Json() {}

    public static String encode(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    public static String escape(String value) {
        StringBuilder sb = new StringBuilder();
        writeString(sb, value);
        return sb.substring(1, sb.length() - 1);
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) sb.append("null");
        else if (value instanceof String s) writeString(sb, s);
        else if (value instanceof Boolean || value instanceof Number) sb.append(value);
        else if (value instanceof Map<?, ?> map) writeMap(sb, map);
        else if (value instanceof Iterable<?> iterable) writeIterable(sb, iterable);
        else writeString(sb, String.valueOf(value));
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            writeString(sb, String.valueOf(entry.getKey()));
            sb.append(':');
            write(sb, entry.getValue());
        }
        sb.append('}');
    }

    private static void writeIterable(StringBuilder sb, Iterable<?> iterable) {
        sb.append('[');
        boolean first = true;
        for (Object value : iterable) {
            if (!first) sb.append(',');
            first = false;
            write(sb, value);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\""); 
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}

