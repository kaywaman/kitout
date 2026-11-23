package org.kitout.application.port;

import java.util.Optional;

/**
 * kitout's own place for secrets: the GitLab token when git's credential helper has none, and secret
 * environment values of services (keys starting with {@code kitout-env/}).
 * Implementations must never log or return the token anywhere except to their caller.
 */
public interface SecretStore {

    Optional<String> lookup(String host);

    void store(String host, String token);

    void delete(String host);

    /** Human-readable name of where the secret lives, e.g. "system keyring". */
    String location();
}
