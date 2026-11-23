package org.kitout.domain.identity;

import java.util.Objects;

/**
 * Who a repository is, independent of where it is checked out or which protocol its remote uses.
 *
 * <p>{@code git@gitlab.example.com:acme/shop/orders.git} and
 * {@code https://gitlab.example.com/acme/shop/orders.git} have the same identity:
 * host {@code gitlab.example.com}, namespace {@code acme/shop}, project {@code orders}.
 */
public record RepoIdentity(String host, String namespace, String project) {

    public RepoIdentity {
        host = requireText(host, "host").toLowerCase();
        namespace = requireText(namespace, "namespace");
        project = requireText(project, "project");
    }

    /** {@code acme/shop/orders}: doubles as the GitLab API project id (URL-encoded). */
    public String fullPath() {
        return namespace + "/" + project;
    }

    /** Stable key used in configuration and as service id prefix: {@code gitlab.example.com/acme/shop/orders}. */
    public String key() {
        return host + "/" + fullPath();
    }


    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
