package com.pubtrack.web;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.temporal.TemporalAccessor;
import java.util.Map;

/**
 * Tiny JSON serializer (no external library). Supports null, strings, numbers, booleans,
 * enums, java.time values, maps, iterables and records (via reflection on record components).
 */
public final class Json {

    private Json() {
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            appendString(sb, s);
        } else if (v instanceof BigDecimal bd) {
            sb.append(bd.toPlainString());
        } else if (v instanceof Double d && (d.isNaN() || d.isInfinite())) {
            sb.append("null"); // JSON has no NaN/Infinity
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Enum<?> e) {
            appendString(sb, e.name());
        } else if (v instanceof TemporalAccessor t) {
            appendString(sb, t.toString()); // ISO-8601, e.g. 2026-11-24T10:15:30
        } else if (v instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                append(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> items) {
            sb.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                append(sb, item);
            }
            sb.append(']');
        } else if (v.getClass().isRecord()) {
            appendRecord(sb, v);
        } else {
            appendString(sb, v.toString());
        }
    }

    private static void appendRecord(StringBuilder sb, Object record) {
        sb.append('{');
        RecordComponent[] components = record.getClass().getRecordComponents();
        for (int i = 0; i < components.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            appendString(sb, components[i].getName());
            sb.append(':');
            try {
                append(sb, components[i].getAccessor().invoke(record));
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Cannot serialise " + record.getClass().getSimpleName(), e);
            }
        }
        sb.append('}');
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '<' -> sb.append("\\u003c"); // keeps "</script>" harmless if JSON is ever inlined in HTML
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
