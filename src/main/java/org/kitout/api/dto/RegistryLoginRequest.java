package org.kitout.api.dto;

/** Write-only: the password or token is handed to {@code docker login} on stdin and not kept by kitout. */
public record RegistryLoginRequest(String username, String password) {
}
