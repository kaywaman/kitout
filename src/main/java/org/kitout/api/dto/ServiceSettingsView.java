package org.kitout.api.dto;

import java.util.List;

/**
 * The per-service settings a developer may change. Null fields keep their current value.
 *
 * @param volumes     host folders mounted into the container (registry image mode); the whole list replaces the old one
 * @param dockerArgs  extra docker run options as typed, e.g. {@code --memory 1g}; an empty text removes them
 * @param healthCheck false: started when the port answers, without asking the readiness check
 * @param port        the port the service answers on instead of the detected one; 0 goes back to the detected one
 * @param script      a Node project's npm script to start it; an empty text goes back to dev/start
 */
public record ServiceSettingsView(String mode, String image, String runtimeProfile, Integer startTimeoutSeconds,
                                  List<VolumeView> volumes, String dockerArgs, Boolean healthCheck, Integer port, String script) {
}
