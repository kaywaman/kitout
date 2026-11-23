package org.kitout.api.dto;

import java.util.List;

/**
 * The Docker page in one call.
 *
 * @param available  false if {@code docker ps} failed (daemon not running, no permission, not installed)
 * @param problem    docker's own reason when not available
 * @param folders     the configured compose folders, with whether each exists
 * @param pickedFiles the compose files picked one by one, with whether each still exists
 */
public record DockerOverviewView(boolean available, String problem, List<ComposeEnvironmentView> environments,
                                 List<ContainerView> containers, List<PathStatusView> folders, List<PathStatusView> pickedFiles) {
}
