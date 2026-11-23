package org.kitout.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Where the team file comes from and what it brings.
 *
 * @param valid              a usable copy is in use (perhaps an older one, see {@code problems})
 * @param missingRepositories the team's repositories that are not cloned on this machine
 * @param secrets            secrets every developer enters (values never sent)
 */
public record TeamStatusView(boolean configured, String location, boolean valid, String team, String version, Instant loadedAt,
                             List<String> problems, List<String> warnings, int services, List<String> groups,
                             List<String> composeEnvironments, List<String> presets, List<String> missingRepositories,
                             List<TeamValueView> secrets, List<TeamValueView> variables) {
}
