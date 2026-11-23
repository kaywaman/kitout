package org.kitout.api.dto;

/**
 * A YAML file in the compose file picker.
 *
 * @param compose     it has a top-level {@code services:} key, so it can be picked
 * @param environment the name it would get; null if not a compose file
 * @param picked      already picked
 */
public record ComposeCandidateView(String name, String path, boolean compose, String environment, boolean picked) {
}
