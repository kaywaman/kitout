package org.kitout.domain.run;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Names of environment variables for the services. A developer may type the Quarkus property
 * ({@code quarkus.datasource.username}); it is stored as the variable Quarkus reads
 * ({@code QUARKUS_DATASOURCE_USERNAME}), following the MicroProfile Config rules.
 */
public final class EnvNames {

    private static final Pattern VALID = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private EnvNames() {
    }

    /** {@code quarkus.datasource."pg".jdbc.url} to {@code QUARKUS_DATASOURCE__PG__JDBC_URL}; {@code %dev.x} to {@code _DEV_X}. */
    public static String fromProperty(String name) {
        String n = name.strip();
        if (VALID.matcher(n).matches()) {
            return n;
        }
        return n.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT);
    }

    /**
     * @throws IllegalArgumentException when the name cannot be an environment variable, or is one kitout sets itself
     */
    public static void validate(String name) {
        if (!VALID.matcher(name).matches()) {
            throw new IllegalArgumentException("'" + name + "' is not a valid environment variable name");
        }
        if (name.equals("QUARKUS_PROFILE")) {
            throw new IllegalArgumentException("QUARKUS_PROFILE is set by kitout from the service's runtime profile; change that instead");
        }
    }
}
