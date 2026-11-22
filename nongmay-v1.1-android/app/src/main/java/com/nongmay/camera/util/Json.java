package com.nongmay.camera.util;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * Minimal JSON writer (no Android dependency, unit-tested on the JVM).
 * Accepts Map (insertion order kept if LinkedHashMap), Collection, arrays, Number, Boolean,
 * CharSequence and null. NaN/Infinity become null because JSON cannot represent them.
 */
public final class Json {
    private Json() {}

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value, -1, 0);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value, 2, 0);
        return sb.append('\n').toString();
    }

    private static void newline(StringBuilder sb, int indent, int depth) {
        if (indent < 0) return;
        sb.append('\n');
        for (int i = 0; i < indent * depth; i++) sb.append(' ');
    }

    @SuppressWarnings("rawtypes")
    private static void append(StringBuilder sb, Object v, int indent, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(trimDouble(d));
        } else if (v instanceof Number) {
            sb.append(v.toString());
        } else if (v instanceof CharSequence || v instanceof Character || v instanceof Enum) {
            quote(sb, v.toString());
        } else if (v instanceof Map) {
            Map m = (Map) v;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            Iterator it = m.entrySet().iterator();
            boolean first = true;
            while (it.hasNext()) {
                Map.Entry e = (Map.Entry) it.next();
                if (!first) sb.append(',');
                first = false;
                newline(sb, indent, depth + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(indent < 0 ? ":" : ": ");
                append(sb, e.getValue(), indent, depth + 1);
            }
            newline(sb, indent, depth);
            sb.append('}');
        } else if (v instanceof Collection) {
            Collection c = (Collection) v;
            if (c.isEmpty()) { sb.append("[]"); return; }
            sb.append('[');
            boolean first = true;
            boolean simple = allScalars(c.toArray());
            for (Object o : c) {
                if (!first) sb.append(simple && indent >= 0 ? ", " : ",");
                first = false;
                if (!simple) newline(sb, indent, depth + 1);
                append(sb, o, indent, depth + 1);
            }
            if (!simple) newline(sb, indent, depth);
            sb.append(']');
        } else if (v.getClass().isArray()) {
            int n = Array.getLength(v);
            if (n == 0) { sb.append("[]"); return; }
            Object[] boxed = new Object[n];
            for (int i = 0; i < n; i++) boxed[i] = Array.get(v, i);
            append(sb, java.util.Arrays.asList(boxed), indent, depth);
        } else {
            quote(sb, v.toString());
        }
    }

    private static boolean allScalars(Object[] items) {
        for (Object o : items) {
            if (o instanceof Map || o instanceof Collection || (o != null && o.getClass().isArray())) return false;
        }
        return true;
    }

    private static String trimDouble(double d) {
        String s = String.format(java.util.Locale.ROOT, "%.6g", d);
        if (s.contains("e") || s.contains("E")) return s;
        if (s.indexOf('.') >= 0) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
