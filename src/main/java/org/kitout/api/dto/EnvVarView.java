package org.kitout.api.dto;

/**
 * One environment variable. For a secret, {@code value} is always null in answers; {@code set}
 * tells whether a value is stored. In a request, a secret with a null value keeps the stored one.
 *
 * @param use {@code RUN} (when the service runs; the default when missing), {@code BUILD} (when kitout builds it) or {@code BOTH}
 */
public record EnvVarView(String name, String value, boolean secret, boolean set, String use) {
}
