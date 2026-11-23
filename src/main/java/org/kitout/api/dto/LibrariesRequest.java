package org.kitout.api.dto;

import java.util.List;

/**
 * Git checkouts below the home folder to add to the team setup kitout wrote as common libraries.
 *
 * @param kind {@code java-library} or {@code ui-library}
 */
public record LibrariesRequest(List<String> folders, String kind) {
}
