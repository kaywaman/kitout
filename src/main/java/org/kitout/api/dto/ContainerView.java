package org.kitout.api.dto;

/**
 * One container from {@code docker ps}.
 *
 * @param state       running, exited, restarting, paused, created or dead
 * @param status      docker's own words, e.g. "Up 3 minutes (healthy)" or "Exited (1) 2 hours ago"
 * @param environment the kitout compose environment it came from; null if none
 * @param kitoutService the kitout service it runs (registry image mode); null if none
 */
public record ContainerView(String id, String name, String image, String state, String status, String ports,
                            String composeProject, String environment, String kitoutService) {
}
