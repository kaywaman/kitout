package org.kitout.api.dto;

/**
 * The docker login for the container registry in Settings. The secret is never sent back.
 *
 * @param username who Docker is logged in as; null when not logged in or when kitout cannot read the login
 * @param detail   where the login comes from, or why there is none, in one sentence
 */
public record RegistryStatusView(String host, boolean loggedIn, String username, String detail) {
}
