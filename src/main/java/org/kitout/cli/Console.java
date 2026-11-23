package org.kitout.cli;

import org.kitout.domain.doctor.CheckResult;
import org.kitout.domain.doctor.Severity;
import org.kitout.domain.identity.Redactor;

import java.io.PrintStream;
import java.util.List;

/**
 * Terminal output in one place: symbols, colours (only on a terminal) and redaction.
 * Commands print through this class so the CLI looks the same everywhere.
 */
public final class Console {

    public static final int OK = 0;
    public static final int FAILED = 1;
    public static final int BLOCKED = 3;
    /** The machine does not meet the team's requirements. */
    public static final int REQUIREMENTS = 4;

    private static final boolean COLOR = System.console() != null && System.getenv("NO_COLOR") == null;

    private Console() {
    }

    public static void out(String line) {
        System.out.println(Redactor.redact(line));
    }

    public static void err(String line) {
        System.err.println(Redactor.redact(line));
    }

    public static void table(List<String[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        int[] widths = new int[rows.get(0).length];
        rows.forEach(r -> {
            for (int i = 0; i < r.length; i++) {
                widths[i] = Math.max(widths[i], r[i] == null ? 0 : r[i].length());
            }
        });
        PrintStream out = System.out;
        rows.forEach(r -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r.length; i++) {
                String cell = r[i] == null ? "" : r[i];
                sb.append(i == r.length - 1 ? cell : String.format("%-" + (widths[i] + 2) + "s", cell));
            }
            out.println(Redactor.redact(sb.toString().stripTrailing()));
        });
    }

    public static void check(CheckResult r) {
        out(symbol(r.severity()) + " " + r.title() + ": " + r.detail() + (r.advisory() ? " (advisory)" : ""));
        r.items().forEach(i -> out("    - " + i));
        r.fix().ifPresent(f -> out("    fix: " + f));
    }

    public static String symbol(Severity s) {
        return switch (s) {
            case OK -> color("32", "✓");
            case INFO -> color("36", "i");
            case WARNING -> color("33", "!");
            case ERROR -> color("31", "✗");
        };
    }

    public static String fail(String text) {
        return color("31", text);
    }

    public static String warn(String text) {
        return color("33", text);
    }

    public static String ok(String text) {
        return color("32", text);
    }

    private static String color(String code, String text) {
        return COLOR ? "\u001b[" + code + "m" + text + "\u001b[0m" : text;
    }
}
