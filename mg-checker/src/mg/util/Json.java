package mg.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 외부 라이브러리 없이 쓰는 작은 JSON 파서·직렬화기.
 * 객체는 LinkedHashMap, 배열은 ArrayList, 숫자는 Long 또는 Double로 읽는다.
 */
public final class Json {

    private Json() {
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != p.s.length()) {
            throw new IllegalArgumentException("JSON 뒤에 남은 문자가 있음 (위치 " + p.pos + ")");
        }
        return v;
    }

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, -1, 0);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 2, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static String str(Map<String, Object> m, String key) {
        if (m == null) {
            return null;
        }
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 정수로 해석할 수 있으면 Integer, 아니면 null. "21" 같은 문자열 숫자도 받는다. */
    public static Integer intOrNull(Object v) {
        if (v instanceof Long l) {
            return l.intValue();
        }
        if (v instanceof Double d && d == Math.floor(d) && !d.isInfinite()) {
            return d.intValue();
        }
        if (v instanceof String s && s.trim().matches("\\d{1,9}")) {
            return Integer.parseInt(s.trim());
        }
        return null;
    }

    // ───────── writer ─────────

    private static void write(StringBuilder sb, Object v, int indent, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline(sb, indent, depth + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(indent >= 0 ? ": " : ":");
                write(sb, e.getValue(), indent, depth + 1);
            }
            if (!m.isEmpty()) {
                newline(sb, indent, depth);
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            boolean any = false;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                any = true;
                newline(sb, indent, depth + 1);
                write(sb, o, indent, depth + 1);
            }
            if (any) {
                newline(sb, indent, depth);
            }
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static void newline(StringBuilder sb, int indent, int depth) {
        if (indent < 0) {
            return;
        }
        sb.append('\n');
        sb.append(" ".repeat(indent * depth));
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ───────── parser ─────────

    private static final class Parser {
        final String s;
        int pos;
        int depth;

        Parser(String s) {
            if (s == null) {
                throw new IllegalArgumentException("JSON 문자열이 null");
            }
            this.s = s;
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '﻿') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        IllegalArgumentException err(String msg) {
            return new IllegalArgumentException(msg + " (위치 " + pos + ")");
        }

        Object value() {
            if (pos >= s.length()) {
                throw err("JSON이 중간에 끝남");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    return literal("true", Boolean.TRUE);
                case 'f':
                    return literal("false", Boolean.FALSE);
                case 'n':
                    return literal("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw err("예상하지 못한 문자 '" + c + "'");
            }
        }

        Object literal(String word, Object v) {
            if (!s.startsWith(word, pos)) {
                throw err("잘못된 값");
            }
            pos += word.length();
            return v;
        }

        Map<String, Object> object() {
            if (++depth > 200) {
                throw err("너무 깊게 중첩됨");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (peek() == '}') {
                pos++;
                depth--;
                return m;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw err("객체 키는 문자열이어야 함");
                }
                String key = string();
                skipWs();
                if (peek() != ':') {
                    throw err("':' 가 필요함");
                }
                pos++;
                skipWs();
                m.put(key, value());
                skipWs();
                char c = peek();
                pos++;
                if (c == ',') {
                    continue;
                }
                if (c == '}') {
                    depth--;
                    return m;
                }
                throw err("',' 또는 '}' 가 필요함");
            }
        }

        List<Object> array() {
            if (++depth > 200) {
                throw err("너무 깊게 중첩됨");
            }
            List<Object> list = new ArrayList<>();
            pos++;
            skipWs();
            if (peek() == ']') {
                pos++;
                depth--;
                return list;
            }
            while (true) {
                skipWs();
                list.add(value());
                skipWs();
                char c = peek();
                pos++;
                if (c == ',') {
                    continue;
                }
                if (c == ']') {
                    depth--;
                    return list;
                }
                throw err("',' 또는 ']' 가 필요함");
            }
        }

        char peek() {
            if (pos >= s.length()) {
                throw err("JSON이 중간에 끝남");
            }
            return s.charAt(pos);
        }

        String string() {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw err("문자열이 닫히지 않음");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw err("잘못된 이스케이프");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw err("잘못된 유니코드 이스케이프");
                            }
                            try {
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw err("잘못된 유니코드 이스케이프");
                            }
                            pos += 4;
                        }
                        default -> throw err("잘못된 이스케이프 \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            boolean isDouble = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isDouble = true;
                    pos++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, pos);
            try {
                if (!isDouble) {
                    try {
                        return Long.parseLong(num);
                    } catch (NumberFormatException overflow) {
                        return Double.parseDouble(num);
                    }
                }
                return Double.parseDouble(num);
            } catch (NumberFormatException ex) {
                throw err("잘못된 숫자 '" + num + "'");
            }
        }
    }
}
