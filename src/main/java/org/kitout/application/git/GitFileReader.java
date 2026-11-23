package org.kitout.application.git;

import org.kitout.application.port.CommandRunner;
import org.kitout.application.port.CommandRunner.Command;
import org.kitout.application.port.CommandRunner.Result;
import org.kitout.domain.identity.Redactor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Reads one file of a repository without cloning it, with git itself, so it works with any git
 * server and with the developer's own git login (SSH key, credential helper).
 *
 * <p>Each repository gets a small bare cache: a shallow, blob-less fetch of the branch (commits and
 * folder listings, no file contents), then only the one file asked for. Later reads fetch what
 * changed. Servers without partial clone (rare) send the branch's files once; that still works.
 *
 * <p>The file is printed with {@code git cat-file blob} only once it is in the cache, so nothing but
 * its content is in the output.
 */
public final class GitFileReader {

    /** A file kitout reads this way: the team file or a compose file. */
    static final long MAX_BYTES = 1024 * 1024;

    /** The repository or the branch was found, but the file is not in it. */
    public static final class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    /** The text and the commit it was read at. */
    public record Content(String text, String commit) {
    }

    private final CommandRunner runner;
    private final Supplier<Map<String, String>> env;
    private final List<String> removedEnv;

    /**
     * @param env        git's environment: no prompts, batch SSH, kitout's askpass (see {@link GitEnvironment})
     * @param removedEnv variables an IDE terminal sets that would open a dialog
     */
    public GitFileReader(CommandRunner runner, Supplier<Map<String, String>> env, List<String> removedEnv) {
        this.runner = runner;
        this.env = env;
        this.removedEnv = removedEnv;
    }

    /**
     * @param cache where this repository's cache goes (created when missing)
     * @param url   the clone URL ({@code https://...} or {@code git@host:group/project.git})
     * @param ref   branch or tag; {@code HEAD} or empty: the default branch
     * @throws NotFound              the file is not in the repository at that branch
     * @throws IllegalStateException git failed (no access, unknown branch, network); the message says git's reason
     */
    public synchronized Content read(Path cache, String url, String path, String ref) {
        String branch = ref == null || ref.isBlank() ? "HEAD" : ref.strip();
        prepare(cache, url);
        Result fetched = git(cache, Duration.ofMinutes(2), "fetch", "--quiet", "--depth", "1", "--filter=blob:none", "--no-tags", "origin", branch);
        if (!fetched.succeeded()) {
            String why = Redactor.redact(fetched.firstLine());
            if (why.contains("couldn't find remote ref") || why.contains("not our ref")) {
                throw new IllegalStateException("there is no branch or tag '" + branch + "' in " + url);
            }
            throw new IllegalStateException("git could not read " + Redactor.redact(url) + ": " + why);
        }
        String commit = git(cache, Duration.ofSeconds(15), "rev-parse", "FETCH_HEAD").output().strip();
        Result blob = git(cache, Duration.ofSeconds(15), "rev-parse", "--verify", "--quiet", "FETCH_HEAD:" + path);
        if (!blob.succeeded() || blob.output().isBlank()) {
            throw new NotFound(path + " is not in " + Redactor.redact(url) + " on " + branch);
        }
        String oid = blob.output().strip();
        if (!git(cache, Duration.ofSeconds(15), "cat-file", "-e", oid).succeeded()) {
            // blob-less fetch: get just this file
            Result one = git(cache, Duration.ofMinutes(1), "fetch", "--quiet", "--no-tags", "--no-write-fetch-head", "origin", oid);
            if (!one.succeeded()) {
                throw new IllegalStateException("git could not read " + path + " from " + Redactor.redact(url) + ": " + Redactor.redact(one.firstLine()));
            }
        }
        Result size = git(cache, Duration.ofSeconds(15), "cat-file", "-s", oid);
        if (size.succeeded() && size.output().strip().matches("\\d+") && Long.parseLong(size.output().strip()) > MAX_BYTES) {
            throw new IllegalStateException(path + " is larger than 1 MB");
        }
        Result text = git(cache, Duration.ofSeconds(15), "cat-file", "blob", oid);
        if (!text.succeeded()) {
            throw new IllegalStateException("git could not read " + path + ": " + Redactor.redact(text.firstLine()));
        }
        return new Content(text.output(), commit);
    }

    private void prepare(Path cache, String url) {
        if (Files.isRegularFile(cache.resolve("HEAD"))) {
            git(cache, Duration.ofSeconds(15), "remote", "set-url", "origin", url);   // the clone protocol may have changed
            return;
        }
        try {
            Files.createDirectories(cache);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create " + cache + ": " + e.getMessage(), e);
        }
        run(Duration.ofSeconds(15), "git", "init", "--quiet", "--bare", cache.toString());
        git(cache, Duration.ofSeconds(15), "remote", "add", "origin", url);
        // a partial clone's settings, so fetches may leave file contents out
        git(cache, Duration.ofSeconds(15), "config", "remote.origin.promisor", "true");
        git(cache, Duration.ofSeconds(15), "config", "remote.origin.partialclonefilter", "blob:none");
    }

    private Result git(Path cache, Duration timeout, String... args) {
        List<String> all = new ArrayList<>(List.of("git", "-C", cache.toString()));
        all.addAll(List.of(args));
        return run(timeout, all.toArray(String[]::new));
    }

    private Result run(Duration timeout, String... args) {
        return runner.run(Command.of(args).withEnv(env.get()).withoutEnv(removedEnv).withTimeout(timeout));
    }
}
