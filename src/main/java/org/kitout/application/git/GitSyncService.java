package org.kitout.application.git;

import org.kitout.application.port.CommandRunner.Result;
import org.kitout.domain.identity.Redactor;
import org.kitout.domain.project.Project;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Keeps the local checkouts up to date with GitLab: find out which are behind, then pull them.
 *
 * <p>Pulling is deliberately careful, because these are the developer's working copies:
 * <ul>
 *   <li>fast-forward only ({@code git pull --ff-only}): never a merge commit, never a rebase;</li>
 *   <li>a checkout with uncommitted changes to tracked files is skipped, not touched;</li>
 *   <li>a branch without an upstream, or a detached HEAD, is skipped with the reason;</li>
 *   <li>a branch that has diverged from origin fails with "merge or rebase in your IDE".</li>
 * </ul>
 * Credentials come from {@link GitEnvironment}, as for clones. Every message is redacted.
 */
@ApplicationScoped
public class GitSyncService {

    static final int PARALLEL = 4;

    /**
     * Where one checkout stands against origin, after a fetch.
     *
     * @param problem why ahead/behind are unknown or a pull would be skipped; null when all is clear
     */
    public record SyncStatus(String serviceId, String name, String branch, String upstream, int ahead, int behind,
                             boolean localChanges, String problem) {

        /** A pull would bring new commits and is safe to run. */
        public boolean pullable() {
            return problem == null && behind > 0 && !localChanges;
        }
    }

    public enum PullStatus { UPDATED, UP_TO_DATE, SKIPPED, FAILED }

    public record PullOutcome(String serviceId, String name, PullStatus status, String message, int newCommits) {
    }

    /** What the developer is told for a service in IDE mode: kitout leaves its checkout to the IDE. */
    public static final String IN_IDE = "In your IDE: pull it there";

    private final GitCli git;
    private final GitEnvironment environment;
    private final org.kitout.application.config.ConfigRepository config;

    public GitSyncService(GitCli git, GitEnvironment environment) {
        this(git, environment, null);
    }

    /** @param config for the run mode: a service in IDE mode is never pulled by kitout */
    @jakarta.inject.Inject
    public GitSyncService(GitCli git, GitEnvironment environment, org.kitout.application.config.ConfigRepository config) {
        this.git = git;
        this.environment = environment;
        this.config = config;
    }

    private boolean inIde(Project p) {
        return config != null && config.load().serviceSettings(p.id()).runMode() == org.kitout.domain.run.RunMode.IDE;
    }

    /** Fetches each checkout and reports branch, ahead/behind and local changes. Changes no files. */
    public List<SyncStatus> check(List<Project> projects) {
        return inParallel(projects, this::checkOne);
    }

    /** Pulls each checkout that can be pulled safely; reports what happened to every one. */
    public List<PullOutcome> pull(List<Project> projects) {
        return inParallel(projects, this::pullOne);
    }

    /** Fetching changes no files, so an IDE-mode checkout is fetched too, but never offered for a pull. */
    SyncStatus checkOne(Project p) {
        SyncStatus s = checkOne(p.id(), p.name(), p.path());
        return inIde(p) && s.problem() == null
                ? new SyncStatus(s.serviceId(), s.name(), s.branch(), s.upstream(), s.ahead(), s.behind(), s.localChanges(), IN_IDE)
                : s;
    }

    /** A service in IDE mode: its checkout belongs to the IDE, kitout does not pull it. */
    PullOutcome pullOne(Project p) {
        if (inIde(p)) {
            return outcome(p.id(), p.name(), PullStatus.SKIPPED, IN_IDE, 0);
        }
        return pullOne(p.id(), p.name(), p.path());
    }

    /** Pulls a repository that is not a service (e.g. the team's compose files), with the same care. */
    public PullOutcome pullRepository(String id, String name, java.nio.file.Path path) {
        return pullOne(id, name, path);
    }

    SyncStatus checkOne(String id, String name, java.nio.file.Path path) {
        Optional<String> branch = git.currentBranch(path);
        if (branch.isEmpty()) {
            return status(id, name, "", "", 0, 0, false, "Not a git checkout");
        }
        boolean changes = git.hasLocalChanges(path);
        if (branch.get().equals("HEAD")) {
            return status(id, name, "detached", "", 0, 0, changes, "Detached HEAD: check out a branch to pull");
        }
        Result fetch = git.fetch(path, environment.env(), environment.removed());
        if (!fetch.succeeded()) {
            return status(id, name, branch.get(), "", 0, 0, changes, "Fetch failed: " + explain(fetch));
        }
        Optional<String> upstream = git.upstream(path);
        if (upstream.isEmpty()) {
            return status(id, name, branch.get(), "", 0, 0, changes, "Branch " + branch.get() + " has no upstream on origin");
        }
        int[] counts = parseAheadBehind(git.aheadBehind(path).orElse(""));
        return status(id, name, branch.get(), upstream.get(), counts[0], counts[1], changes, null);
    }

    PullOutcome pullOne(String id, String name, java.nio.file.Path path) {
        SyncStatus s = checkOne(id, name, path);
        if (s.problem() != null) {
            return outcome(id, name, s.problem().startsWith("Fetch failed") ? PullStatus.FAILED : PullStatus.SKIPPED, s.problem(), 0);
        }
        if (s.behind() == 0) {
            return outcome(id, name, PullStatus.UP_TO_DATE, "Already up to date with " + s.upstream(), 0);
        }
        if (s.localChanges()) {
            return outcome(id, name, PullStatus.SKIPPED, "Uncommitted changes: commit or stash them, then pull again", 0);
        }
        String before = git.head(path).orElse("");
        Result pull = git.pullFastForward(path, environment.env(), environment.removed());
        if (!pull.succeeded()) {
            return outcome(id, name, PullStatus.FAILED, explain(pull), 0);
        }
        int commits = before.isEmpty() ? s.behind() : git.countCommits(path, before, "HEAD");
        return outcome(id, name, PullStatus.UPDATED, commits + (commits == 1 ? " new commit" : " new commits") + " from " + s.upstream(), commits);
    }

    /** {@code "2\t5"} (ahead, behind) to {@code [2, 5]}; anything unreadable counts as zero. */
    static int[] parseAheadBehind(String output) {
        String[] parts = output.strip().split("\\s+");
        if (parts.length != 2) {
            return new int[] {0, 0};
        }
        try {
            return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException e) {
            return new int[] {0, 0};
        }
    }

    /** One sentence from git's output: the known cases in plain words, otherwise git's own first error line. */
    static String explain(Result r) {
        if (r.timedOut()) {
            return "git did not answer in time; check the network and VPN";
        }
        String out = r.output();
        String lower = out.toLowerCase(Locale.ROOT);
        if (lower.contains("not possible to fast-forward") || lower.contains("diverging branches") || lower.contains("have diverged")) {
            return "Your branch and origin have diverged: merge or rebase in your IDE";
        }
        if (lower.contains("authentication failed") || lower.contains("could not read username") || lower.contains("permission denied")) {
            return "The git server refused the credentials: check your login or token (Settings, Git token)";
        }
        if (lower.contains("could not resolve host") || lower.contains("connection timed out") || lower.contains("unable to access")) {
            return "The git server is not reachable: check the network and VPN";
        }
        if (lower.contains("would be overwritten")) {
            return "Local changes would be overwritten: commit or stash them, then pull again";
        }
        for (String line : out.split("\\R")) {
            String l = line.strip();
            if (l.startsWith("fatal:") || l.startsWith("error:")) {
                return Redactor.redact(l);
            }
        }
        String first = r.firstLine();
        return first.isEmpty() ? "git exited with code " + r.exitCode() : Redactor.redact(first);
    }

    private static SyncStatus status(String id, String name, String branch, String upstream, int ahead, int behind,
                                     boolean changes, String problem) {
        return new SyncStatus(id, name, branch, upstream, ahead, behind, changes, problem);
    }

    private static PullOutcome outcome(String id, String name, PullStatus status, String message, int commits) {
        return new PullOutcome(id, name, status, Redactor.redact(message), commits);
    }

    /** Runs {@code task} for every project, {@link #PARALLEL} at a time, keeping the input order. */
    private static <T> List<T> inParallel(List<Project> projects, Function<Project, T> task) {
        Semaphore permits = new Semaphore(PARALLEL);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<T>> futures = new ArrayList<>();
            for (Project p : projects) {
                futures.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        return task.apply(p);
                    } finally {
                        permits.release();
                    }
                }));
            }
            List<T> results = new ArrayList<>();
            for (var f : futures) {
                try {
                    results.add(f.get());
                } catch (Exception e) {
                    throw new IllegalStateException("Checking the repositories was interrupted", e);
                }
            }
            return results;
        }
    }
}
