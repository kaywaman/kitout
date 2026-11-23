package org.kitout.application.git;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.git.RemoteCleanupService.Finding;
import org.kitout.application.git.RemoteCleanupService.Outcome;
import org.kitout.application.git.RemoteCleanupService.Status;
import org.kitout.application.port.CommandRunner.Command;
import org.kitout.domain.identity.RemoteUrlParser;
import org.kitout.domain.project.BuildTool;
import org.kitout.domain.project.Project;
import org.kitout.infrastructure.process.ProcessCommandRunner;
import org.kitout.testsupport.Fakes;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses the real git command on temporary repositories. GitLab itself is never contacted. */
class RemoteCleanupServiceTest {

    private static final String HOST = "gitlab.invalid";   // .invalid never resolves: the access test fails
    private final ProcessCommandRunner runner = new ProcessCommandRunner();
    private final GitCli git = new GitCli(runner);
    private final Fakes.InMemorySecrets secrets = new Fakes.InMemorySecrets();

    private RemoteCleanupService service(Path home) {
        Fakes.InMemoryConfig config = new Fakes.InMemoryConfig(KitoutConfig.defaults()
                .withGitlab(new KitoutConfig.GitLabSettings(HOST, "https", "~/work", "", null)));
        CredentialService credentials = new CredentialService(git, secrets);
        KitoutPaths paths = new KitoutPaths(Map.of("KITOUT_HOME", home.toString()));
        GitEnvironment env = new GitEnvironment(credentials, config, paths, new SelfCommand() {
            @Override
            public List<String> command() {
                return List.of("/bin/false");
            }
        });
        return new RemoteCleanupService(git, env, credentials);
    }

    private Project repo(Path dir, String origin, String pushUrl) {
        run("git", "init", "-q", dir.toString());
        run("git", "-C", dir.toString(), "remote", "add", "origin", origin);
        if (pushUrl != null) {
            run("git", "-C", dir.toString(), "remote", "set-url", "--push", "origin", pushUrl);
        }
        return new Project(dir.getFileName().toString(), dir.getFileName().toString(), dir,
                RemoteUrlParser.parse(origin), BuildTool.MAVEN, 8080, false, Optional.empty(), List.of(), Map.of(),
                new org.kitout.domain.config.HealthEndpoint(8080, "/q/health/ready", false));
    }

    private void run(String... args) {
        assertTrue(runner.run(Command.of(args)).succeeded(), String.join(" ", args));
    }

    @Test
    void findsTokensInFetchAndPushUrlsWithoutExposingThem() throws Exception {
        Path tmp = Files.createTempDirectory("kitout-test");
        Project dirty = repo(tmp.resolve("orders"), "https://99999:SECRET@" + HOST + "/acme/orders.git",
                "https://u:SECRET2@" + HOST + "/acme/orders.git");
        Project clean = repo(tmp.resolve("billing"), "https://" + HOST + "/acme/billing.git", null);

        List<Finding> findings = service(tmp).find(List.of(dirty, clean));

        assertEquals(2, findings.size());
        findings.forEach(f -> {
            assertFalse(f.describe().contains("SECRET"), f.describe());
            assertEquals("https://" + HOST + "/acme/orders.git", f.cleanUrl());
        });
    }

    @Test
    void refusesToCleanBeforeATokenIsStored() throws Exception {
        Path tmp = Files.createTempDirectory("kitout-test");
        Project dirty = repo(tmp.resolve("orders"), "https://99999:SECRET@" + HOST + "/acme/orders.git", null);
        assertThrows(IllegalStateException.class, () -> service(tmp).apply(List.of(dirty)));
        assertTrue(git.originUrl(dirty.path()).orElseThrow().contains("SECRET"), "nothing may change");
    }

    @Test
    void rollsBackWhenTheAccessTestFails() throws Exception {
        Path tmp = Files.createTempDirectory("kitout-test");
        String original = "https://99999:SECRET@" + HOST + "/acme/orders.git";
        Project dirty = repo(tmp.resolve("orders"), original, null);
        secrets.store(HOST, "new-token");

        List<Outcome> outcomes = service(tmp).apply(List.of(dirty));

        assertEquals(1, outcomes.size());
        assertEquals(Status.ROLLED_BACK, outcomes.get(0).status());
        assertFalse(outcomes.get(0).message().contains("SECRET"));
        assertEquals(original, git.originUrl(dirty.path()).orElseThrow(), "the old URL is restored");
    }

    @Test
    void readsAllRemotesIncludingDottedNames() throws Exception {
        Path tmp = Files.createTempDirectory("kitout-test");
        Project p = repo(tmp.resolve("orders"), "https://" + HOST + "/acme/orders.git", null);
        run("git", "-C", p.path().toString(), "remote", "add", "my.fork", "https://x:T@" + HOST + "/me/orders.git");
        List<GitCli.RemoteUrl> urls = git.remoteUrls(p.path());
        assertTrue(urls.stream().anyMatch(u -> u.remote().equals("my.fork") && u.key().equals("url")));
    }
}
