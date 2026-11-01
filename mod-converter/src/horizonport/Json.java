package horizonport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A small JSON reader and writer, so the converter needs nothing but a JDK. */
final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** Parses JSON into Map (ordered), List, String, Double/Long, Boolean or null. */
    static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.error("trailing characters");
        return v;
    }

    private Object value() {
        if (i >= s.length()) throw error("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            if (peek() != '"') throw error("expected a key");
            String k = string();
            ws();
            if (peek() != ':') throw error("expected ':'");
            i++;
            ws();
            m.put(k, value());
            ws();
            char c = peek();
            i++;
            if (c == '}') return m;
            if (c != ',') throw error("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = peek();
            i++;
            if (c == ']') return l;
            if (c != ',') throw error("expected ',' or ']'");
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) throw error("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                default -> b.append(e);
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(start, i);
        if (n.isEmpty()) throw error("unexpected character");
        if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
        return Long.parseLong(n);
    }

    private void expect(String word) {
        if (!s.startsWith(word, i)) throw error("expected " + word);
        i += word.length();
    }

    private char peek() {
        if (i >= s.length()) throw error("unexpected end");
        return s.charAt(i);
    }

    /** Skips whitespace, and the // comments some mod authors leave in fabric.mod.json. */
    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) i++;
            else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
            } else break;
        }
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException("JSON: " + msg + " at character " + i);
    }

    // ---- writing ----

    static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, 0);
        b.append('\n');
        return b.toString();
    }

    private static void write(StringBuilder b, Object v, int indent) {
        if (v == null) b.append("null");
        else if (v instanceof String str) quote(b, str);
        else if (v instanceof Number || v instanceof Boolean) b.append(v);
        else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append("{\n");
            int n = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                pad(b, indent + 1);
                quote(b, String.valueOf(e.getKey()));
                b.append(": ");
                write(b, e.getValue(), indent + 1);
                if (++n < m.size()) b.append(',');
                b.append('\n');
            }
            pad(b, indent);
            b.append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) { b.append("[]"); return; }
            b.append("[\n");
            for (int k = 0; k < l.size(); k++) {
                pad(b, indent + 1);
                write(b, l.get(k), indent + 1);
                if (k + 1 < l.size()) b.append(',');
                b.append('\n');
            }
            pad(b, indent);
            b.append(']');
        } else quote(b, v.toString());
    }

    private static void pad(StringBuilder b, int indent) {
        b.append("  ".repeat(indent));
    }

    private static void quote(StringBuilder b, String str) {
        b.append('"');
        for (char c : str.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }

    // ---- helpers for reading parsed values ----

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object v) {
        return v instanceof Map<?, ?> ? (Map<String, Object>) v : Map.of();
    }

    static List<?> list(Object v) {
        if (v == null) return List.of();
        return v instanceof List<?> l ? l : List.of(v);
    }

    static String str(Object v) {
        return v instanceof String s ? s : null;
    }
}
