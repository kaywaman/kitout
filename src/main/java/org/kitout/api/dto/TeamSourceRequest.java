package org.kitout.api.dto;

/**
 * Which team file to follow. In GitLab: {@code repo} and {@code path}, like a team compose file, with the
 * host and the branch ({@code ref}, default {@code main}). On this machine: {@code file}.
 *
 * @param location the older single text: a GitLab link ({@code .../-/blob/<ref>/<path>}), {@code host/group/project},
 *                 or a local path. Used only when neither {@code repo} nor {@code file} is given
 * @param path     the file's path in the repository; empty: {@code kitout-team.json}
 */
public record TeamSourceRequest(String location, String host, String repo, String path, String ref, String file) {

    public TeamSourceRequest(String location) {
        this(location, null, null, null, null, null);
    }
}
