package org.kitout.domain.config;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Parses {@code application.properties} and {@code application.yaml} text into flat keys.
 *
 * <p>The YAML support covers what Quarkus projects use for ports and URLs: nested maps with
 * scalar values, quoted keys such as {@code "%dev"}, and comments. Lists and multi-line scalars
 * are skipped, which is fine because kitout never needs them.
 */
public final class ConfigFileParser {

    private ConfigFileParser() {
    }

    public static Map<String, String> parseProperties(String text) {
        Properties props = new Properties();
        try {
            props.load(new StringReader(text));
        } catch (IOException | IllegalArgumentException e) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        props.stringPropertyNames().forEach(k -> result.put(k, props.getProperty(k)));
        return result;
    }

    public static Map<String, String> parseYaml(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        Deque<int[]> indents = new ArrayDeque<>();   // indentation of each open level
        Deque<String> keys = new ArrayDeque<>();      // key of each open level

        for (String line : text.split("\\R")) {
            String content = stripComment(line);
            if (content.isBlank() || content.trim().startsWith("-") || content.trim().equals("---")) {
                continue;
            }
            int indent = content.length() - content.stripLeading().length();
            int colon = findKeyColon(content);
            if (colon < 0) {
                continue;
            }
            while (!indents.isEmpty() && indents.peek()[0] >= indent) {
                indents.pop();
                keys.pop();
            }
            String key = unquote(content.substring(indent, colon).trim());
            String value = content.substring(colon + 1).trim();
            if (value.isEmpty() || value.equals("|") || value.equals(">")) {
                indents.push(new int[] {indent});
                keys.push(key);
            } else {
                result.put(join(keys, key), unquote(value));
            }
        }
        return result;
    }

    private static String join(Deque<String> parents, String key) {
        StringBuilder sb = new StringBuilder();
        parents.descendingIterator().forEachRemaining(p -> sb.append(p).append('.'));
        return sb.append(key).toString();
    }

    /** Colon that ends a key: outside quotes and followed by a space or the end of the line. */
    private static int findKeyColon(String s) {
        boolean inQuote = false;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == quote) {
                    inQuote = false;
                }
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quote = c;
            } else if (c == ':' && (i + 1 == s.length() || s.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static String stripComment(String line) {
        boolean inQuote = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuote) {
                if (c == quote) {
                    inQuote = false;
                }
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quote = c;
            } else if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
