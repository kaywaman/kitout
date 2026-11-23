package org.kitout.api.dto;

import java.util.List;

/**
 * The tags the tag picker offers for one service: newest-looking first, never {@code latest}.
 *
 * @param current the tag chosen now; null if none yet
 */
public record ImageTagsView(String serviceId, String repository, List<String> tags, String current) {
}
