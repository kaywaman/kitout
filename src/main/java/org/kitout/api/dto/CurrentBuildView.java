package org.kitout.api.dto;

import java.time.Instant;

/**
 * Build output on disk that can run now, one per service and mode.
 *
 * @param attemptId the kitout build that made it; null when built outside kitout (IDE, terminal)
 * @param usedBy    why it cannot be removed now (the service runs from it, a build is writing it); null if it can
 */
public record CurrentBuildView(String serviceId, String serviceName, String mode, String version, Instant builtAt, long sizeBytes,
                               String path, String attemptId, boolean removable, String usedBy) {
}
