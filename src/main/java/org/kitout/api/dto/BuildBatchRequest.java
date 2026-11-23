package org.kitout.api.dto;

import java.util.List;

/** Build several services with the same options. {@code mode} is JVM or NATIVE. */
public record BuildBatchRequest(List<String> serviceIds, String profile, String mode, boolean skipTests) {
}
