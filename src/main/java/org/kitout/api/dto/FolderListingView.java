package org.kitout.api.dto;

import java.util.List;

/** {@code parent} is null at the home folder, which is as far up as the picker goes. */
public record FolderListingView(String path, String parent, String home, List<FolderEntryView> entries, boolean truncated) {
}
