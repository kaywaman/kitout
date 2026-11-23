package org.kitout.api.dto;

public record CloneCandidateView(String name, String fullPath, String defaultBranch, String target, boolean alreadyLocal) {
}
