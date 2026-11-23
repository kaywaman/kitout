package org.kitout.api.dto;

/** A configured path and whether it still exists. */
public record PathStatusView(String path, boolean exists) {
}
