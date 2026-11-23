package org.kitout.api.dto;

import java.time.Instant;

/**
 * {@code queuePosition} is 1 for the next native build to run, 0 when not waiting for the native permit.
 *
 * @param version       the project's version when the build started; null if unknown
 * @param removable     the build can be taken off the list now
 * @param notRemovable  why not (still running, or the newest build of a running service); null if removable
 * @param outputNote    for a succeeded attempt whose output is gone: why (replaced by a later build); null for the current build
 */
public record BuildView(String id, String serviceId, String serviceName, String profile, String mode, boolean skipTests,
                        String status, int queuePosition, String waitingFor, Instant createdAt, Instant startedAt,
                        Instant finishedAt, ExplanationView explanation, boolean prodProfile, String version,
                        boolean removable, String notRemovable, String outputNote) {
}
