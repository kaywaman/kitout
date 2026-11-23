package org.kitout.api.dto;

import java.util.List;

/** One folder in the compose file picker: its sub-folders and its YAML files. */
public record ComposeBrowseView(String path, String parent, String home, List<FolderEntryView> folders,
                                List<ComposeCandidateView> files) {
}
