package org.kitout.domain.git;

import org.kitout.application.config.KitoutConfig;
import org.kitout.application.team.TeamOverlay;
import org.kitout.domain.team.TeamSetup;
import org.kitout.domain.team.TeamSetupParser;
import org.kitout.testsupport.TeamFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHostTest {

    @Test
    void theKindIsSetOrGuessedFromTheHostName() {
        assertEquals(GitHost.GITHUB, GitHost.of("", "github.com"));
        assertEquals(GitHost.GITHUB, GitHost.of(null, "github.example.com"));
        assertEquals(GitHost.GITLAB, GitHost.of("", "gitlab.example.com"));
        assertEquals(GitHost.GIT, GitHost.of("auto", "git.example.com"), "a plain name: any git server");
        assertEquals(GitHost.GITLAB, GitHost.of("GitLab", "git.example.com"), "set: wins over the name");
        assertThrows(IllegalArgumentException.class, () -> GitHost.of("bitbucket", "x"));
    }

    @Test
    void eachKindHasItsUsualRegistry() {
        assertEquals("registry.gitlab.example.com", new KitoutConfig.GitLabSettings("gitlab.example.com", "https", "~/work", "", "").registryHost());
        assertEquals("ghcr.io", new KitoutConfig.GitLabSettings("github.com", "https", "~/work", "", "").registryHost());
        assertEquals("", new KitoutConfig.GitLabSettings("git.example.com", "https", "~/work", "", "").registryHost(), "no convention: set it");
        assertEquals("registry.git.example.com",
                new KitoutConfig.GitLabSettings("git.example.com", "https", "~/work", "", "", "gitlab").registryHost());
        assertEquals("git@github.com:acme/orders.git", new KitoutConfig.GitLabSettings("github.com", "ssh", "~/work", "", "").cloneUrl("acme/orders"));
        assertEquals("https://github.com/acme/orders.git", new KitoutConfig.GitLabSettings("https://github.com/", "https", "", "", "").cloneUrl("acme/orders"));
    }

    @Test
    void theTeamFileNamesItsServerUnderGit() {
        Map<String, Object> json = TeamFixtures.filledJson();
        json.put("git", new java.util.LinkedHashMap<>(Map.of("host", "github.com", "cloneProtocol", "ssh")));
        json.remove("registry");
        TeamSetupParser.Result r = TeamSetupParser.parse(json, "1.0.0");
        TeamSetup team = r.setup().orElseThrow(() -> new AssertionError(r.problems().toString()));

        KitoutConfig applied = TeamOverlay.apply(KitoutConfig.defaults(), team, LocalDate.now());
        assertEquals("github.com", applied.gitlab().host());
        assertEquals(GitHost.GITHUB, applied.gitlab().kind());
        assertEquals("ghcr.io", applied.gitlab().registryHost());

        json.put("gitlab", Map.of("host", "gitlab.example.com"));
        assertTrue(TeamSetupParser.parse(json, "1.0.0").problems().stream().anyMatch(p -> p.contains("both \"git\" and \"gitlab\"")));

        json.remove("gitlab");
        json.put("git", Map.of("host", "git.example.com", "provider", "gitea"));
        assertTrue(TeamSetupParser.parse(json, "1.0.0").problems().stream().anyMatch(p -> p.startsWith("git.provider is gitlab, github or git")));
    }
}
