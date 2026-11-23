package org.kitout.api.dto;

/**
 * Where the GitLab token comes from. Never contains the token.
 *
 * @param source GIT_CREDENTIAL_HELPER, KITOUT_KEYRING or NONE
 */
public record CredentialStatusView(String host, String source, boolean hasFallbackToken, String fallbackLocation) {
}
