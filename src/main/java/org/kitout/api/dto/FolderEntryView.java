package org.kitout.api.dto;

/** A sub-folder in the folder picker; no file names or contents are ever sent. */
public record FolderEntryView(String name, String path, boolean quarkusProject, boolean gitRepository) {
}
