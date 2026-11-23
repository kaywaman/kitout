package org.kitout.application.git;

import org.kitout.application.port.CommandRunner;
import org.kitout.application.port.CommandRunner.Command;
import org.kitout.application.port.CommandRunner.Result;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The git command line, as the rest of kitout needs it. Calls {@code git} rather than a Java
 * library, which keeps the native build simple and behaves exactly like the developer's git.
 *
 * <p>Network operations take an environment from {@link GitEnvironment}, which disables
 * terminal prompts and supplies credentials without putting them in arguments.
 */
@ApplicationScoped
public class GitCli {

    /** One configured remote URL; {@code key} is {@code url} or {@code pushurl}. */
    public record RemoteUrl(String remote, String key, String url) {
    }

    private final CommandRunner runner;

    public GitCli(CommandRunner runner) {
        this.runner = runner;
    }

    public Optional<String> originUrl(Path repo) {
        Result r = runner.run(Command.of("git", "-C", repo.toString(), "config", "--get", "remote.origin.url"));
        return r.succeeded() && !r.output().isBlank() ? Optional.of(r.output().strip()) : Optional.empty();
    }

    /** Every fetch and push URL of every remote. Values may contain credentials: never log them raw. */
    public List<RemoteUrl> remoteUrls(Path repo) {
        Result r = runner.run(Command.of("git", "-C", repo.toString(), "config", "--get-regexp", "^remote\\..*\\.(url|pushurl)$"));
        List<RemoteUrl> result = new ArrayList<>();
        if (!r.succeeded()) {
            return result;
        }
        for (String line : r.output().split("\\R")) {
            int space = line.indexOf(' ');
            if (space < 0) {
                continue;
            }
            String name = line.substring(0, space);           // remote.<remote>.<key>
            int lastDot = name.lastIndexOf('.');
            String remote = name.substring("remote.".length(), lastDot);
            result.add(new RemoteUrl(remote, name.substring(lastDot + 1), line.substring(space + 1).strip()));
        }
        return result;
    }

    public boolean setRemoteUrl(Path repo, RemoteUrl remote, String newUrl) {
        List<String> args = new ArrayList<>(List.of("git", "-C", repo.toString(), "remote", "set-url"));
        if (remote.key().equals("pushurl")) {
            args.add("--push");
        }
        args.add(remote.remote());
        args.add(newUrl);
        return runner.run(Command.of(args)).succeeded();
    }

    public Result lsRemote(Path repo, Map<String, String> env, List<String> removeEnv) {
        return runner.run(Command.of("git", "-C", repo.toString(), "ls-remote", "origin", "HEAD")
                .withEnv(env).withoutEnv(removeEnv).withTimeout(Duration.ofSeconds(30)));
    }

    public Result lsRemoteUrl(String url, Map<String, String> env, List<String> removeEnv) {
        return runner.run(Command.of("git", "ls-remote", url, "HEAD")
                .withEnv(env).withoutEnv(removeEnv).withTimeout(Duration.ofSeconds(30)));
    }

    /** @param light blob-less clone: full history, file contents fetched on demand. Enough for the scanner. */
    public Result clone(String url, Path target, boolean light, Map<String, String> env, List<String> removeEnv) {
        List<String> args = new ArrayList<>(List.of("git", "clone"));
        if (light) {
            args.add("--filter=blob:none");
        }
        args.add(url);
        args.add(target.toString());
        return runner.run(Command.of(args).withEnv(env).withoutEnv(removeEnv).withTimeout(Duration.ofMinutes(30)));
    }

    // ---- keeping a checkout up to date (GitSyncService) ----------------------------------

    /** Updates the remote-tracking branches; changes nothing in the working tree. */
    public Result fetch(Path repo, Map<String, String> env, List<String> removeEnv) {
        return runner.run(Command.of("git", "-C", repo.toString(), "fetch", "--quiet", "--prune", "origin")
                .withEnv(env).withoutEnv(removeEnv).withTimeout(Duration.ofMinutes(2)));
    }

    /** The checked-out branch, or {@code HEAD} when detached. */
    public Optional<String> currentBranch(Path repo) {
        return value(Command.of("git", "-C", repo.toString(), "rev-parse", "--abbrev-ref", "HEAD"));
    }

    /** The branch this one pulls from, e.g. {@code origin/main}; empty if none is set. */
    public Optional<String> upstream(Path repo) {
        return value(Command.of("git", "-C", repo.toString(), "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}"));
    }

    /** {@code "<ahead>\t<behind>"} relative to the upstream, as {@code git rev-list --left-right --count}. */
    public Optional<String> aheadBehind(Path repo) {
        return value(Command.of("git", "-C", repo.toString(), "rev-list", "--left-right", "--count", "HEAD...@{u}"));
    }

    /** Changes to tracked files, staged or not. Untracked files do not count: a pull keeps them. */
    public boolean hasLocalChanges(Path repo) {
        Result r = runner.run(Command.of("git", "-C", repo.toString(), "status", "--porcelain", "--untracked-files=no"));
        return !r.succeeded() || !r.output().isBlank();
    }

    public Optional<String> head(Path repo) {
        return value(Command.of("git", "-C", repo.toString(), "rev-parse", "HEAD"));
    }

    /** Fast-forward only: never creates a merge commit and never rewrites local work. */
    public Result pullFastForward(Path repo, Map<String, String> env, List<String> removeEnv) {
        return runner.run(Command.of("git", "-C", repo.toString(), "pull", "--ff-only", "--quiet")
                .withEnv(env).withoutEnv(removeEnv).withTimeout(Duration.ofMinutes(5)));
    }

    /** Commits reachable from {@code to} but not from {@code from}. */
    public int countCommits(Path repo, String from, String to) {
        return value(Command.of("git", "-C", repo.toString(), "rev-list", "--count", from + ".." + to))
                .map(v -> {
                    try {
                        return Integer.parseInt(v);
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                }).orElse(0);
    }

    private Optional<String> value(Command command) {
        Result r = runner.run(command);
        return r.succeeded() && !r.output().isBlank() ? Optional.of(r.output().strip()) : Optional.empty();
    }

    public Result credentialFill(String host) {
        return runner.run(Command.of("git", "credential", "fill")
                .withStdin("protocol=https\nhost=" + host + "\n\n")
                .withEnv(Map.of("GIT_TERMINAL_PROMPT", "0"))
                .withoutEnv(List.of("GIT_ASKPASS", "SSH_ASKPASS"))
                .withTimeout(Duration.ofSeconds(10)));
    }

    /** A value from the user's git configuration (system, global and includes, as git itself reads it). */
    public Optional<String> userConfig(String key) {
        Result r = runner.run(Command.of("git", "config", "--get", key));
        return r.succeeded() && !r.output().isBlank() ? Optional.of(r.output().strip()) : Optional.empty();
    }

    public Result version() {
        return runner.run(Command.of("git", "--version"));
    }
}
