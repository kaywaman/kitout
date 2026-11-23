package org.kitout.api.dto;

/**
 * A team file location, split like a team compose file: {@code repo} and {@code path} in GitLab, or a local {@code file}.
 * Fields not known are null (a suggestion may carry only the host).
 */
public record TeamSourceView(String host, String repo, String path, String ref, String file) {
}
