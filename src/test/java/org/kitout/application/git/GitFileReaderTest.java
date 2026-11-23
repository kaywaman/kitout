package org.kitout.application.git;

import org.kitout.application.port.CommandRunner;
import org.kitout.infrastructure.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** With real git, against a repository on disk that serves like GitHub or GitLab do (partial clone allowed). */
class GitFileReaderTest {

    private final CommandRunner runner = new ProcessCommandRunner();

    private void git(Path dir, String... args) {
        List<String> all = new java.util.ArrayList<>(List.of("git", "-C", dir.toString(), "-c", "user.name=t", "-c", "user.email=t@example.com"));
        all.addAll(List.of(args));
        CommandRunner.Result r = runner.run(CommandRunner.Command.of(all).withTimeout(java.time.Duration.ofSeconds(30)));
        assertTrue(r.succeeded(), String.join(" ", all) + ": " + r.output());
    }

    private Path server() throws Exception {
        Path work = Files.createTempDirectory("kitout-server");
        git(work, "init", "--quiet", "-b", "main");
        git(work, "config", "uploadpack.allowFilter", "true");
        git(work, "config", "uploadpack.allowAnySHA1InWant", "true");
        Files.createDirectories(work.resolve("bw/kitout"));
        Files.writeString(work.resolve("bw/kitout/kitout-team.json"), "{\"team\": \"one\"}\n");
        Files.writeString(work.resolve("big-unrelated.bin"), "x".repeat(200_000));
        git(work, "add", ".");
        git(work, "commit", "--quiet", "-m", "first");
        git(work, "checkout", "--quiet", "-b", "release");
        Files.writeString(work.resolve("bw/kitout/kitout-team.json"), "{\"team\": \"release\"}\n");
        git(work, "commit", "--quiet", "-am", "on release");
        git(work, "checkout", "--quiet", "main");
        return work;
    }

    @Test
    void readsOneFileOfABranchWithoutCloning() throws Exception {
        Path server = server();
        String url = "file://" + server;
        Path cache = Files.createTempDirectory("kitout-cache").resolve("acme/team.git");
        GitFileReader reader = new GitFileReader(runner, () -> Map.of("GIT_TERMINAL_PROMPT", "0"), List.of());

        GitFileReader.Content main = reader.read(cache, url, "bw/kitout/kitout-team.json", "main");
        assertEquals("{\"team\": \"one\"}\n", main.text(), "the exact content, no git messages in it");
        assertEquals(40, main.commit().length());
        assertEquals("{\"team\": \"release\"}\n", reader.read(cache, url, "bw/kitout/kitout-team.json", "release").text());
        assertEquals("{\"team\": \"one\"}\n", reader.read(cache, url, "bw/kitout/kitout-team.json", "HEAD").text(), "HEAD: the default branch");

        // blob-less: the other files' contents were never downloaded (git lists them as missing)
        CommandRunner.Result missing = runner.run(CommandRunner.Command.of("git", "-C", cache.toString(), "rev-list", "--objects",
                "--missing=print", "FETCH_HEAD"));
        assertTrue(missing.output().lines().anyMatch(l -> l.startsWith("?")), "file contents left out:\n" + missing.output());

        git(server, "checkout", "--quiet", "main");
        Files.writeString(server.resolve("bw/kitout/kitout-team.json"), "{\"team\": \"two\"}\n");
        git(server, "commit", "--quiet", "-am", "second");
        assertEquals("{\"team\": \"two\"}\n", reader.read(cache, url, "bw/kitout/kitout-team.json", "main").text(), "a later read sees the change");

        assertThrows(GitFileReader.NotFound.class, () -> reader.read(cache, url, "kitout-team.json", "main"));
        IllegalStateException branch = assertThrows(IllegalStateException.class, () -> reader.read(cache, url, "bw/kitout/kitout-team.json", "nope"));
        assertTrue(branch.getMessage().contains("no branch or tag 'nope'"), branch.getMessage());
    }

    @Test
    void anUnreachableRepositorySaysWhatGitSaid() throws Exception {
        Path cache = Files.createTempDirectory("kitout-cache").resolve("acme/gone.git");
        GitFileReader reader = new GitFileReader(runner, () -> Map.of("GIT_TERMINAL_PROMPT", "0"), List.of());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> reader.read(cache, "file:///nowhere/acme/gone.git", "kitout-team.json", "main"));
        assertTrue(e.getMessage().startsWith("git could not read file:///nowhere/acme/gone.git: "), e.getMessage());
    }

    @Test
    void hintsSayWhatUsuallyHelps() {
        assertTrue(RepositoryFiles.hint("fatal: could not read Username for 'https://github.com': terminal prompts disabled", "github.com", "https")
                .contains("gh auth setup-git"));
        assertTrue(RepositoryFiles.hint("git@github.com: Permission denied (publickey).", "github.com", "ssh").contains("SSH key"));
        assertEquals("", RepositoryFiles.hint("something else", "github.com", "https"));
    }
}
