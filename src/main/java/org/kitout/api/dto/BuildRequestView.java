package org.kitout.api.dto;

/** {@code mode} is JVM or NATIVE; {@code profile} e.g. dev, qa, prod. */
public record BuildRequestView(String profile, String mode, boolean skipTests) {
}
