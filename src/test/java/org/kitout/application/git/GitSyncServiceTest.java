package org.kitout.application.git;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.git.GitSyncService.PullOutcome;
import org.kitout.application.git.GitSyncService.PullStatus;
import org.kitout.application.git.GitSyncService.SyncStatus;
import org.kitout.application.port.CommandRunner.Command;
import org.kitout.application.port.CommandRunner.Result;
import org.kitout.domain.config.HealthEndpoint;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real git on temporary repositories: a bare "origin", the developer's checkout, and a colleague's. */
class GitSyncServiceTest {

    private static final Map<String, String> AUTHOR = Map.of("GIT_AUTHOR_NAME", "t", "GIT_AUTHOR_EMAIL", "t@example.com",
            "GIT_COMMITTER_NAME", "t", "GIT_COMMITTER_EMAIL", "t@example.com");
    private final ProcessCommandRunner runner = new ProcessCommandRunner();
    private final GitCli git = new GitCli(runner);

    private GitSyncService service(Path home) {
        return service(home, null);
    }

    private GitSyncService service(Path home, org.kitout.domain.run.RunMode ordersMode) {
        Fakes.InMemoryConfig config = new Fakes.InMemoryConfig(KitoutConfig.defaults()
                .withGitlab(new KitoutConfig.GitLabSettings("gitlab.invalid", "https", "~/work", "", null)));
        GitEnvironment env = new GitEnvironment(new CredentialService(git, new Fakes.InMemorySecrets()), config,
                new KitoutPaths(Map.of("KITOUT_HOME", home.toString())), new SelfCommand() {
                    @Override
                    public List<String> command() {
                        return List.of("/bin/false");
                    }
                });
        if (ordersMode != null) {
            config.update(c -> c.withService("acme.orders", KitoutConfig.ServiceSettings.defaults("/w/orders").withRunMode(ordersMode)));
        }
        return new GitSyncService(git, env, config);
    }

    /** origin with one commit; "mine" and "theirs" are clones of it. */
    private record Repos(Path tmp, Path origin, Path mine, Path theirs) {
    }

    private Repos repos() throws Exception {
        Path tmp = Files.createTempDirectory("kitout-sync");
        Path origin = tmp.resolve("origin.git");
        Path seed = tmp.resolve("seed");
        run("git", "init", "-q", "--bare", "-b", "main", origin.toString());
        run("git", "init", "-q", "-b", "main", seed.toString());
        commit(seed, "README.md", "hello");
        run("git", "-C", seed.toString(), "remote", "add", "origin", origin.toString());
        run("git", "-C", seed.toString(), "push", "-q", "-u", "origin", "main");
        run("git", "clone", "-q", origin.toString(), tmp.resolve("mine").toString());
        run("git", "clone", "-q", origin.toString(), tmp.resolve("theirs").toString());
        return new Repos(tmp, origin, tmp.resolve("mine"), tmp.resolve("theirs"));
    }

    private void commit(Path repo, String file, String content) throws Exception {
        Files.writeString(repo.resolve(file), content);
        run("git", "-C", repo.toString(), "add", file);
        run("git", "-C", repo.toString(), "commit", "-q", "-m", "change " + file);
    }

    private void theyPush(Repos r, String file) throws Exception {
        commit(r.theirs(), file, "from a colleague");
        run("git", "-C", r.theirs().toString(), "push", "-q", "origin", "main");
    }

    private void run(String... args) {
        Result result = runner.run(Command.of(args).withEnv(AUTHOR));
        assertTrue(result.succeeded(), String.join(" ", args) + ": " + result.output());
    }

    private static Project project(Path dir) {
        return new Project("acme.orders", "orders", dir, RemoteUrlParser.parse("https://gitlab.invalid/acme/orders.git"),
                BuildTool.MAVEN, 8080, false, Optional.empty(), List.of(), Map.of(), new HealthEndpoint(8080, "/q/health/ready", false));
    }

    @Test
    void checkFindsNewCommitsAndPullBringsThem() throws Exception {
        Repos r = repos();
        theyPush(r, "a.txt");
        theyPush(r, "b.txt");
        GitSyncService sync = service(r.tmp());

        SyncStatus status = sync.check(List.of(project(r.mine()))).get(0);
        assertEquals("main", status.branch());
        assertEquals("origin/main", status.upstream());
        assertEquals(2, status.behind());
        assertTrue(status.pullable());
        assertFalse(Files.exists(r.mine().resolve("a.txt")), "check changes no files");

        PullOutcome pulled = sync.pull(List.of(project(r.mine()))).get(0);
        assertEquals(PullStatus.UPDATED, pulled.status(), pulled.message());
        assertEquals(2, pulled.newCommits());
        assertTrue(Files.exists(r.mine().resolve("b.txt")));

        assertEquals(PullStatus.UP_TO_DATE, sync.pull(List.of(project(r.mine()))).get(0).status());
    }

    @Test
    void aServiceInIdeModeIsFetchedButNeverPulled() throws Exception {
        Repos r = repos();
        theyPush(r, "a.txt");
        GitSyncService sync = service(r.tmp(), org.kitout.domain.run.RunMode.IDE);

        SyncStatus status = sync.check(List.of(project(r.mine()))).get(0);
        assertEquals(1, status.behind(), "fetching changes no files, so it is still checked");
        assertFalse(status.pullable());
        assertEquals(GitSyncService.IN_IDE, status.problem());

        PullOutcome outcome = sync.pull(List.of(project(r.mine()))).get(0);
        assertEquals(PullStatus.SKIPPED, outcome.status());
        assertEquals("In your IDE: pull it there", outcome.message());
        assertFalse(Files.exists(r.mine().resolve("a.txt")), "the IDE's checkout is untouched");
    }

    @Test
    void uncommittedChangesAreNeverTouched() throws Exception {
        Repos r = repos();
        theyPush(r, "a.txt");
        Files.writeString(r.mine().resolve("README.md"), "my unfinished work");

        PullOutcome outcome = service(r.tmp()).pull(List.of(project(r.mine()))).get(0);

        assertEquals(PullStatus.SKIPPED, outcome.status());
        assertTrue(outcome.message().contains("Uncommitted changes"), outcome.message());
        assertEquals("my unfinished work", Files.readString(r.mine().resolve("README.md")));
        assertFalse(Files.exists(r.mine().resolve("a.txt")));
    }

    @Test
    void aDivergedBranchIsNotMerged() throws Exception {
        Repos r = repos();
        theyPush(r, "a.txt");
        commit(r.mine(), "mine.txt", "local commit");

        GitSyncService sync = service(r.tmp());
        SyncStatus status = sync.check(List.of(project(r.mine()))).get(0);
        assertEquals(1, status.ahead());
        assertEquals(1, status.behind());

        PullOutcome outcome = sync.pull(List.of(project(r.mine()))).get(0);
        assertEquals(PullStatus.FAILED, outcome.status());
        assertTrue(outcome.message().contains("diverged"), outcome.message());
        assertFalse(Files.exists(r.mine().resolve("a.txt")), "no merge happened");
    }

    @Test
    void aBranchWithoutUpstreamIsSkipped() throws Exception {
        Repos r = repos();
        run("git", "-C", r.mine().toString(), "checkout", "-q", "-b", "feature/local-only");

        PullOutcome outcome = service(r.tmp()).pull(List.of(project(r.mine()))).get(0);

        assertEquals(PullStatus.SKIPPED, outcome.status());
        assertTrue(outcome.message().contains("no upstream"), outcome.message());
    }

    @Test
    void readsCountsAndExplainsFailures() {
        assertArrayEquals(new int[] {2, 5}, GitSyncService.parseAheadBehind("2\t5\n"));
        assertArrayEquals(new int[] {0, 0}, GitSyncService.parseAheadBehind("garbage"));
        assertEquals("The git server refused the credentials: check your login or token (Settings, Git token)",
                GitSyncService.explain(new Result(128, "remote: HTTP Basic: Access denied\nfatal: Authentication failed for 'https://gitlab/x.git/'")));
        assertFalse(GitSyncService.explain(new Result(1, "fatal: unable to read https://u:glpat-abcdefghijklmnopqrst@h/x"))
                .contains("glpat-abcdefghijklmnopqrst"), "messages are redacted");
    }
}
