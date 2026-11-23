package org.kitout.api.dto;

import java.util.List;

/** A team setup built from scratch; empty values mean "not yet". */
public record TeamDraftRequest(String team, String gitlabHost, String cloneFolder, String registryHost, String imagePath,
                               List<String> composeFiles, String envFile, boolean recordRequirements) {
}
