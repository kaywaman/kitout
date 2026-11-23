package org.kitout.testsupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A small JSON reader for tests that read the shipped example files without starting Quarkus. */
public final class MiniJson {

    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        MiniJson p = new MiniJson(text);
        Object v = p.value();
        p.ws();
        if (p.i != text.length()) {
            throw new IllegalArgumentException("Trailing text at " + p.i);
        }
        return (Map<String, Object>) v;
    }

    private Object value() {
        ws();
        char c = s.charAt(i);
        switch (c) {
            case '{': {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    ws();
                    String k = string();
                    ws();
                    expect(':');
                    m.put(k, value());
                    ws();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect('}');
                    return m;
                }
            }
            case '[': {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    l.add(value());
                    ws();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect(']');
                    return l;
                }
            }
            case '"':
                return string();
            case 't':
                i += 4;
                return Boolean.TRUE;
            case 'f':
                i += 5;
                return Boolean.FALSE;
            case 'n':
                i += 4;
                return null;
            default: {
                int start = i;
                while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                    i++;
                }
                String n = s.substring(start, i);
                if (n.contains(".") || n.contains("e") || n.contains("E")) {
                    return Double.parseDouble(n);
                }
                long l = Long.parseLong(n);
                return l == (int) l ? (Object) (int) l : (Object) l;
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (s.charAt(i) != '"') {
            char c = s.charAt(i++);
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> b.append(e);
                }
            } else {
                b.append(c);
            }
        }
        i++;
        return b.toString();
    }

    private void expect(char c) {
        if (s.charAt(i) != c) {
            throw new IllegalArgumentException("Expected " + c + " at " + i + " but found " + s.charAt(i));
        }
        i++;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    /** Indented JSON, maps in their order; enough for the files kitout writes. */
    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(v, b, "");
        return b.append('\n').toString();
    }

    private static void write(Object v, StringBuilder b, String indent) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof Map<?, ?> m) {
            b.append("{");
            String inner = indent + "  ";
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                b.append(i++ == 0 ? "\n" : ",\n").append(inner);
                string(String.valueOf(e.getKey()), b);
                b.append(": ");
                write(e.getValue(), b, inner);
            }
            b.append(m.isEmpty() ? "}" : "\n" + indent + "}");
        } else if (v instanceof List<?> l) {
            b.append("[");
            for (int i = 0; i < l.size(); i++) {
                b.append(i == 0 ? "" : ", ");
                write(l.get(i), b, indent);
            }
            b.append("]");
        } else if (v instanceof String s) {
            string(s, b);
        } else {
            b.append(v);
        }
    }

    private static void string(String s, StringBuilder b) {
        b.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")).append('"');
    }

    /** A JsonReader for tests, without Jackson. */
    public static org.kitout.application.port.JsonReader reader() {
        return new org.kitout.application.port.JsonReader() {
            @Override
            public Map<String, Object> readObject(String json) {
                return object(json);
            }

            @Override
            public String writePretty(Object value) {
                return write(value);
            }
        };
    }
}
