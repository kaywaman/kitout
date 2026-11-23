package org.kitout.api.dto;

/**
 * One repository of the team.
 *
 * @param kind  service, or repository (needed by the team, e.g. compose files, never a service)
 * @param state CONFIGURED (loaded in kitout), LOCAL (on this machine, not loaded yet) or MISSING (to clone)
 */
public record SetupProjectView(String repo, String serviceId, String kind, String state, String path, String description) {
}
