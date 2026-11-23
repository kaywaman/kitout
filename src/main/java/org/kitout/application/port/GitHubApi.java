package org.kitout.application.port;

import java.util.List;
import java.util.Optional;

/** The GitHub REST calls kitout needs: github.com, or GitHub Enterprise Server on its own host. */
public interface GitHubApi {

    /**
     * The repositories of an organization, or of a user when no organization has that name; archived
     * ones left out. Public repositories need no token; private ones need one that can see them.
     */
    List<GitLabApi.Project> ownerRepositories(String host, String owner, Optional<String> token);
}
