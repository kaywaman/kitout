package org.kitout.domain.run;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads a {@code .env} file as docker compose writes them: {@code NAME=value}, optional
 * {@code export}, single or double quotes, {@code #} comments. Nothing is expanded or executed.
 */
public final class DotEnv {

    private static final Pattern SECRET_NAME = Pattern.compile("(?i).*(PASSWORD|PASSWD|PWD|SECRET|TOKEN|API_?KEY|PRIVATE_?KEY|CREDENTIAL).*");

    private DotEnv() {
    }

    public static Map<String, String> parse(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring(7).strip();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                boolean doubleQuoted = value.startsWith("\"");
                value = value.substring(1, value.length() - 1);
                if (doubleQuoted) {
                    value = value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
                }
            } else {
                int hash = value.indexOf(" #");
                if (hash >= 0) {
                    value = value.substring(0, hash).strip();
                }
            }
            values.put(name, value);
        }
        return values;
    }

    /** Names that hold passwords and keys by convention: imported as secrets (keyring, never shown). */
    public static boolean looksSecret(String name) {
        return SECRET_NAME.matcher(name.toUpperCase(Locale.ROOT)).matches();
    }
}
