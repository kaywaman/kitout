package org.kitout.infrastructure.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kitout.application.port.GitLabApi;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpGitHubApiTest {

    @Test
    void readsAPageOfRepositories() throws Exception {
        String page = """
                [{"name": "orders", "full_name": "acme/orders", "clone_url": "https://github.com/acme/orders.git",
                  "ssh_url": "git@github.com:acme/orders.git", "default_branch": "main", "archived": false, "private": true},
                 {"name": "old", "full_name": "acme/old", "clone_url": "https://github.com/acme/old.git",
                  "ssh_url": "git@github.com:acme/old.git", "default_branch": "master", "archived": true}]
                """;
        List<GitLabApi.Project> projects = HttpGitHubApi.projects(new ObjectMapper().readTree(page));
        assertEquals(new GitLabApi.Project("orders", "acme/orders", "https://github.com/acme/orders.git", "git@github.com:acme/orders.git",
                "main", false), projects.get(0));
        assertTrue(projects.get(1).archived(), "archived ones are filtered after paging");
    }

    @Test
    void followsGitHubsPaging() {
        assertTrue(HttpGitHubApi.hasNext("<https://api.github.com/organizations/1/repos?page=2>; rel=\"next\", <https://api.github.com/organizations/1/repos?page=5>; rel=\"last\""));
        assertFalse(HttpGitHubApi.hasNext("<https://api.github.com/organizations/1/repos?page=1>; rel=\"first\", <https://api.github.com/organizations/1/repos?page=4>; rel=\"prev\""));
        assertFalse(HttpGitHubApi.hasNext(""));
    }

    @Test
    void githubComAndEnterpriseHaveTheirOwnApiAddress() {
        assertEquals("https://api.github.com", HttpGitHubApi.apiBase("github.com"));
        assertEquals("https://github.example.com/api/v3", HttpGitHubApi.apiBase("github.example.com"));
    }
}
