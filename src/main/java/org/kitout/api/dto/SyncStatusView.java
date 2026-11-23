package org.kitout.api.dto;

/**
 * Where a checkout stands against origin, after a fetch.
 *
 * @param pullable a pull would bring new commits and is safe (no local changes, no problem)
 * @param problem  why it cannot be pulled, e.g. "Detached HEAD"; null when all is clear
 */
public record SyncStatusView(String serviceId, String serviceName, String branch, String upstream, int ahead, int behind,
                             boolean localChanges, boolean pullable, String problem) {
}
