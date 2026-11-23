package org.kitout.domain.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only view of a Quarkus project's configuration files, resolved for one profile.
 *
 * <p>Implements the rules a developer expects from Quarkus itself (least astonishment):
 * <ul>
 *   <li>{@code %dev.key} wins over {@code key} when the profile is {@code dev}</li>
 *   <li>{@code ${other.key:default}} placeholders are resolved, also inside values</li>
 *   <li>environment variables are unknown at file level, so {@code ${ENV:default}} uses the default
 *       and a placeholder without default leaves the value unresolved ({@link #isUnresolved})</li>
 * </ul>
 * Values are what the files say, not what the running service sees. Callers that report on them
 * must label their result as advisory.
 */
public final class QuarkusConfig {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?}");
    private static final int MAX_DEPTH = 10;
    private static final String UNRESOLVED = "\u0000unresolved\u0000";

    private final Map<String, String> raw;
    private final String profile;

    public QuarkusConfig(Map<String, String> raw, String profile) {
        this.raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
        this.profile = profile;
    }

    public static QuarkusConfig empty(String profile) {
        return new QuarkusConfig(Map.of(), profile);
    }

    public String profile() {
        return profile;
    }

    /** The resolved value, or empty if the key is absent or its value cannot be resolved from files. */
    public Optional<String> get(String key) {
        String value = resolve(key, 0);
        return value == null || value.contains(UNRESOLVED) ? Optional.empty() : Optional.of(value);
    }

    /** True if the key exists but depends on something only known at runtime. */
    public boolean isUnresolved(String key) {
        String value = resolve(key, 0);
        return value != null && value.contains(UNRESOLVED);
    }

    public Optional<Integer> getInt(String key) {
        return get(key).flatMap(v -> {
            try {
                return Optional.of(Integer.parseInt(v.trim()));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    /** All effective keys (profile prefix removed) that start with the given prefix. */
    public Map<String, String> withPrefix(String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : raw.keySet()) {
            String plain = stripProfile(key);
            if (plain != null && plain.startsWith(prefix) && !result.containsKey(plain)) {
                get(plain).ifPresent(v -> result.put(plain, v));
            }
        }
        return result;
    }

    private String stripProfile(String key) {
        if (!key.startsWith("%")) {
            return key;
        }
        String own = "%" + profile + ".";
        return key.startsWith(own) ? key.substring(own.length()) : null;
    }

    private String resolve(String key, int depth) {
        if (depth > MAX_DEPTH) {
            return UNRESOLVED;
        }
        String value = raw.get("%" + profile + "." + key);
        if (value == null) {
            value = raw.get(key);
        }
        return value == null ? null : expand(value, depth);
    }

    private String expand(String value, int depth) {
        Matcher m = PLACEHOLDER.matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String referenced = resolve(m.group(1).trim(), depth + 1);
            String replacement;
            if (referenced != null) {
                replacement = referenced;
            } else if (m.group(2) != null) {
                replacement = expand(m.group(2), depth + 1);
            } else {
                replacement = UNRESOLVED;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }
}
