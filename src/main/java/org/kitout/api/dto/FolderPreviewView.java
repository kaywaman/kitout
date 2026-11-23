package org.kitout.api.dto;

import java.util.List;

/** What adding a folder would configure; {@code hiddenPaths} are projects removed earlier. */
public record FolderPreviewView(String path, List<FolderPreviewProjectView> projects, List<String> hiddenPaths, List<String> outsideTeam) {
}
