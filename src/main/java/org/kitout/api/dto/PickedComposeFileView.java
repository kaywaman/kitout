package org.kitout.api.dto;

/** A compose file the developer picked, and the environment it became. */
public record PickedComposeFileView(String environment, String file) {
}
