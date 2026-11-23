package org.kitout.domain.build;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A request to build one service.
 *
 * @param profile Quarkus build profile, e.g. {@code dev}, {@code qa}, {@code prod}
 */
public record BuildRequest(String serviceId, String profile, BuildMode mode, boolean skipTests) {

    private static final Pattern PROFILE = Pattern.compile("[a-zA-Z0-9_-]{1,32}");

    public BuildRequest {
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(mode, "mode");
        if (profile == null || !PROFILE.matcher(profile).matches()) {
            throw new IllegalArgumentException("Profile must be 1-32 letters, digits, '-' or '_'");
        }
    }

    /** Local builds with the prod profile are allowed, but the UI and CLI warn. */
    public boolean isProdProfile() {
        return profile.equals("prod");
    }
}
