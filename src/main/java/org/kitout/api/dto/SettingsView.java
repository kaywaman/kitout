package org.kitout.api.dto;

import java.util.List;

public record SettingsView(List<String> workspaceRoots, GitLab gitlab, Build build, Doctor doctor, Docker docker) {

    /**
     * The git server (named {@code gitlab} for history; any git server works).
     *
     * @param imagePath where images are in the registry: {@code {namespace}/{project}} (default), {@code {project}}, ...
     * @param provider  as set: {@code gitlab}, {@code github}, {@code git}, or empty (guessed from the host)
     * @param kind      what kitout uses: {@code GITLAB}, {@code GITHUB} or {@code GIT}; ignored when saving
     */
    public record GitLab(String host, String cloneProtocol, String workspaceRoot, String registryHost, String imagePath,
                         String provider, String kind) {
    }

    public record Build(boolean nativeContainerBuild, int nativeMinFreeMemoryMb, List<String> profiles) {
    }

    public record Doctor(boolean embeddedCredentialsAsError) {
    }

    /**
     * Folders with docker-compose-&lt;environment&gt;.yml files, one environment per file, and files picked one by one.
     * {@code composeFiles} may be left out (null) when saving: the picked files are then kept.
     */
    public record Docker(List<String> composeDirs, List<String> composeFiles, Integer keepImageVersions) {
    }
}
