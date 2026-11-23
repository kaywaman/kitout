package org.kitout.domain.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads one top-level string field from JSON text without a JSON library (the domain has none). Good enough for package.json. */
public final class JsonText {

    private JsonText() {
    }

    /** The first {@code "field": "value"} in the text, or null. */
    public static String stringField(String json, String field) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
