package org.kitout.domain.team;

import java.util.List;
import java.util.Locale;

/**
 * Which Java distribution printed a {@code java -version} output. Several distributions print
 * "OpenJDK Runtime Environment" followed by their name (Temurin, Zulu, ...); a plain OpenJDK build
 * (e.g. Ubuntu's openjdk-21-jdk) names no one.
 */
public final class JavaVendor {

    private static final List<String[]> KNOWN = List.of(
            new String[] {"temurin", "Temurin"},
            new String[] {"zulu", "Zulu"},
            new String[] {"corretto", "Corretto"},
            new String[] {"graalvm", "GraalVM"},
            new String[] {"microsoft", "Microsoft"},
            new String[] {"semeru", "Semeru"},
            new String[] {"bellsoft", "Liberica"},
            new String[] {"liberica", "Liberica"},
            new String[] {"sapmachine", "SapMachine"},
            new String[] {"java(tm) se", "Oracle"});

    private JavaVendor() {
    }

    public static String detect(String javaVersionOutput) {
        String out = javaVersionOutput.toLowerCase(Locale.ROOT);
        for (String[] k : KNOWN) {
            if (out.contains(k[0])) {
                return k[1];
            }
        }
        return out.contains("openjdk") ? "OpenJDK" : "unknown";
    }

    /** The version in {@code openjdk version "21.0.5" 2024-10-15}; empty if there is none. */
    public static String version(String javaVersionOutput) {
        int q = javaVersionOutput.indexOf("version \"");
        if (q < 0) {
            return "";
        }
        int start = q + "version \"".length();
        int end = javaVersionOutput.indexOf('"', start);
        return end < 0 ? "" : javaVersionOutput.substring(start, end);
    }
}
