package org.kitout.application.port;

import java.util.Optional;

/**
 * The developer's container registry logins, read from Docker's own store (what {@code docker login}
 * wrote: {@code ~/.docker/config.json} or a credential helper). kitout keeps no copy: Docker, kitout's
 * pulls and the tag picker all use the same login, which is separate from the GitLab token.
 */
public interface RegistryCredentials {

    /** A login. {@code secret} is a password or token: never log it, never put it in a URL or argument. */
    record Credential(String username, String secret) {

        @Override
        public String toString() {
            return "Credential[username=" + username + ", secret=***]";
        }
    }

    Optional<Credential> lookup(String registryHost);

    /** Why {@link #lookup} finds nothing, in one sentence for the developer (which file, which helper, what it said). */
    default String diagnose(String registryHost) {
        return "";
    }
}
