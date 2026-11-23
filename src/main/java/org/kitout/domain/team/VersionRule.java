package org.kitout.domain.team;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A version requirement from the team file, as people write them:
 * <ul>
 *   <li>{@code 24.04}: exactly this version ({@code 24.4.0} matches too)</li>
 *   <li>{@code 21.*}: any 21 (a prefix)</li>
 *   <li>{@code >=3.9.0 <4.0.0}: all conditions must hold; operators {@code >= > <= < =}</li>
 *   <li>{@code x86_64}: text that is not a version is compared as is (case-insensitive)</li>
 * </ul>
 * Versions found on the machine are read leniently: {@code v22.23.4}, {@code 21.0.5+11-LTS},
 * {@code 2.43.0.windows.1} all yield their leading numbers.
 */
public final class VersionRule {

    private static final Pattern CONDITION = Pattern.compile("(>=|<=|>|<|=)?\\s*([0-9][0-9A-Za-z.*+-]*)");
    private static final Pattern NUMBERS = Pattern.compile("(\\d+(?:\\.\\d+)*)");

    private record Condition(String op, String version) {
    }

    private final String text;
    private final List<Condition> conditions;
    private final String literal;

    private VersionRule(String text, List<Condition> conditions, String literal) {
        this.text = text;
        this.conditions = conditions;
        this.literal = literal;
    }

    /** @throws IllegalArgumentException with a sentence for the team file's author */
    public static VersionRule parse(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("an empty version requirement");
        }
        if (!Character.isDigit(t.charAt(0)) && "<>=".indexOf(t.charAt(0)) < 0) {
            return new VersionRule(t, List.of(), t);
        }
        List<Condition> conditions = new ArrayList<>();
        for (String part : t.split("\\s+")) {
            Matcher m = CONDITION.matcher(part);
            if (!m.matches()) {
                throw new IllegalArgumentException("'" + part + "' in '" + t + "' is not a version condition (write e.g. >=3.9.0 <4.0.0, 21.* or 24.04)");
            }
            String op = m.group(1) == null ? "=" : m.group(1);
            if (m.group(2).contains("*") && !op.equals("=")) {
                throw new IllegalArgumentException("'" + part + "': a wildcard only works without an operator, e.g. 21.*");
            }
            conditions.add(new Condition(op, m.group(2)));
        }
        return new VersionRule(t, conditions, null);
    }

    public boolean matches(String found) {
        if (found == null || found.isBlank()) {
            return false;
        }
        if (literal != null) {
            return literal.toLowerCase(Locale.ROOT).equals(found.strip().toLowerCase(Locale.ROOT));
        }
        int[] actual = numbers(found);
        if (actual.length == 0) {
            return false;
        }
        for (Condition c : conditions) {
            if (c.version().contains("*")) {
                int[] prefix = numbers(c.version().substring(0, c.version().indexOf('*')));
                for (int i = 0; i < prefix.length; i++) {
                    if (i >= actual.length || actual[i] != prefix[i]) {
                        return false;
                    }
                }
                continue;
            }
            int cmp = compare(actual, numbers(c.version()));
            boolean ok = switch (c.op()) {
                case ">=" -> cmp >= 0;
                case ">" -> cmp > 0;
                case "<=" -> cmp <= 0;
                case "<" -> cmp < 0;
                default -> cmp == 0;
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** The leading dotted numbers of a version string; {@code v22.23.4} gives {@code [22, 23, 4]}. */
    static int[] numbers(String version) {
        Matcher m = NUMBERS.matcher(version);
        if (!m.find()) {
            return new int[0];
        }
        String[] parts = m.group(1).split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                result[i] = Integer.MAX_VALUE;
            }
        }
        return result;
    }

    /** Missing trailing parts count as 0: 24.04 equals 24.4.0. */
    static int compare(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    @Override
    public String toString() {
        return text;
    }
}
