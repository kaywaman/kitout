package org.kitout.domain.run;

import org.kitout.domain.identity.RepoIdentity;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a project's images live inside the registry, from a pattern the team sets once:
 * <ul>
 *   <li>{@code {namespace}/{project}}: {@code acme/shop/orders} (GitLab's own registry layout, the default)</li>
 *   <li>{@code {project}}: {@code orders} (registries without groups)</li>
 *   <li>{@code {group}/{project}}: {@code acme/orders}; {@code {path}} is the full GitLab path</li>
 *   <li>fixed text is allowed: {@code services/{project}}</li>
 * </ul>
 */
public final class ImagePath {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z]+)}");
    private static final Pattern VALID = Pattern.compile("[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*");

    private ImagePath() {
    }

    public static String render(String pattern, RepoIdentity identity) {
        String full = identity.fullPath().toLowerCase(Locale.ROOT);
        int slash = full.lastIndexOf('/');
        String namespace = slash < 0 ? "" : full.substring(0, slash);
        String project = full.substring(slash + 1);
        String group = namespace.contains("/") ? namespace.substring(0, namespace.indexOf('/')) : namespace;
        Matcher m = PLACEHOLDER.matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = switch (m.group(1)) {
                case "namespace" -> namespace;
                case "group" -> group;
                case "project" -> project;
                case "path" -> full;
                default -> throw new IllegalArgumentException("Unknown placeholder {" + m.group(1)
                        + "} in the image path; use {namespace}, {group}, {project} or {path}");
            };
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString().replaceAll("/{2,}", "/").replaceAll("^/|/$", "");
    }

    /** Checks a pattern by rendering it for a sample project; the message says what is wrong. */
    public static void validate(String pattern) {
        if (!pattern.contains("{project}") && !pattern.contains("{path}")) {
            throw new IllegalArgumentException("The image path must contain {project} or {path}, or every service would get the same image");
        }
        String sample = render(pattern, new RepoIdentity("gitlab.example.com", "acme/shop", "orders"));
        if (!VALID.matcher(sample).matches()) {
            throw new IllegalArgumentException("The image path '" + pattern + "' gives '" + sample + "', which is not a valid image repository");
        }
    }
}
