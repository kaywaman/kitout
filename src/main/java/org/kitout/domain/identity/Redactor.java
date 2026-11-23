package org.kitout.domain.identity;

import java.util.regex.Pattern;

/**
 * The single place that removes credentials from text before it leaves kitout.
 *
 * <p>Every remote URL and every line of git output shown in the UI, CLI, log files or API
 * responses passes through {@link #redact(String)}. Keep it that way: a second, slightly
 * different redaction function is how leaks happen.
 */
public final class Redactor {

    /**
     * {@code http(s)://user:secret@} or {@code http(s)://secret@} inside any text. SSH URLs are left
     * alone: their user part ({@code git@}) is not a secret, the SSH key is.
     */
    private static final Pattern URL_USERINFO = Pattern.compile("(?i)\\b(https?)://[^/@\\s]+@");

    /** GitLab token prefixes (glpat-, gloas-, ...) that might appear outside URLs. */
    private static final Pattern GITLAB_TOKEN = Pattern.compile("\\bgl[a-z]{2,6}-[A-Za-z0-9_\\-]{16,}");

    private Redactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String withoutUserInfo = URL_USERINFO.matcher(text).replaceAll("$1://");
        return GITLAB_TOKEN.matcher(withoutUserInfo).replaceAll("[redacted]");
    }

    /** True if the URL carries a user or password part, e.g. {@code https://99999:token@host/...}. */
    public static boolean hasEmbeddedCredentials(String url) {
        return url != null && URL_USERINFO.matcher(url).find();
    }

    /** The URL without its user-info part. */
    public static String stripCredentials(String url) {
        return url == null ? null : URL_USERINFO.matcher(url).replaceFirst("$1://");
    }
}
