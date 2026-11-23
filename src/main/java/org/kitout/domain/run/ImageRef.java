package org.kitout.domain.run;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A container image reference split into repository and tag:
 * {@code registry.example.com:5000/acme/shop/orders:mr-123} is the repository
 * {@code registry.example.com:5000/acme/shop/orders} with the tag {@code mr-123}.
 * The colon of a registry port is not a tag separator; a digest ({@code @sha256:...}) counts as the tag.
 */
public record ImageRef(String repository, Optional<String> tag) {

    /** Docker's tag grammar: up to 128 characters, no leading dot or dash. */
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}");

    public static ImageRef parse(String image) {
        String s = image.strip();
        int at = s.indexOf('@');
        if (at > 0) {
            return new ImageRef(s.substring(0, at), Optional.of(s.substring(at)));
        }
        int lastSlash = s.lastIndexOf('/');
        int colon = s.lastIndexOf(':');
        if (colon > lastSlash) {
            return new ImageRef(s.substring(0, colon), Optional.of(s.substring(colon + 1)));
        }
        return new ImageRef(s, Optional.empty());
    }

    public static boolean isValidTag(String tag) {
        return tag != null && TAG.matcher(tag).matches();
    }

    /** The registry host (with port) of the repository; {@code docker.io} when none is named. */
    public String registry() {
        int slash = repository.indexOf('/');
        String first = slash < 0 ? "" : repository.substring(0, slash);
        return first.contains(".") || first.contains(":") || first.equals("localhost") ? first : "docker.io";
    }

    /** The repository path inside the registry, e.g. {@code acme/shop/orders}. */
    public String path() {
        String registry = registry();
        return repository.startsWith(registry + "/") ? repository.substring(registry.length() + 1) : repository;
    }

    public String withTag(String newTag) {
        return newTag.startsWith("@") ? repository + newTag : repository + ":" + newTag;
    }
}
