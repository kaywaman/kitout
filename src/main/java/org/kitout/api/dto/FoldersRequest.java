package org.kitout.api.dto;

import java.util.List;

/** Folders below the home folder, e.g. projects to add to the team setup. */
public record FoldersRequest(List<String> folders) {
}
