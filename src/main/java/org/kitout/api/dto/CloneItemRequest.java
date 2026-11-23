package org.kitout.api.dto;

/** {@code light}: blob-less clone, enough for services that only run from images. */
public record CloneItemRequest(String fullPath, boolean light) {
}
