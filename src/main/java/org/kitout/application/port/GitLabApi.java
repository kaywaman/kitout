package org.kitout.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** The few GitLab REST calls kitout needs. */
public interface GitLabApi {

    record Project(String name, String fullPath, String httpUrl, String sshUrl, String defaultBranch, boolean archived) {
    }

    record TokenInfo(String name, boolean active, List<String> scopes, Optional<LocalDate> expiresAt) {
    }

    /** Projects of a group including subgroups, archived ones excluded. */
    List<Project> groupProjects(String host, String groupPath, String token);

    TokenInfo tokenInfo(String host, String token);

    /** A file from a repository: its text and the commit it was last changed in. */
    record FileContent(String text, String commitId) {
    }

    /** {@code GET /projects/:path/repository/files/:file?ref=}; 404 if the project, file or ref does not exist. */
    FileContent file(String host, String projectPath, String filePath, String ref, String token);

    /** Plain HTTPS request to the host root; throws with a readable message on TLS or network problems. */
    void ping(String host);

    class GitLabException extends RuntimeException {
        private final int status;

        public GitLabException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
