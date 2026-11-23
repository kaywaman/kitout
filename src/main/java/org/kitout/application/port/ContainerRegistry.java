package org.kitout.application.port;

import java.util.List;

/** Reads a container registry through the Docker Registry HTTP API v2 (works for GitLab, Harbor, Nexus, ...). */
public interface ContainerRegistry {

    /** Every tag of {@code repositoryPath} (e.g. {@code acme/shop/orders}) in {@code registryHost}. */
    List<String> tags(String registryHost, String repositoryPath) throws RegistryException;

    final class RegistryException extends Exception {

        public enum Kind { UNAUTHORIZED, NOT_FOUND, UNREACHABLE, OTHER }

        private final Kind kind;

        public RegistryException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }
}
