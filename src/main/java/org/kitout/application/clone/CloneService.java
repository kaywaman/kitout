package org.kitout.application.clone;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.ConfigRepository;
import org.kitout.application.config.KitoutConfig.GitLabSettings;
import org.kitout.application.git.CredentialService;
import org.kitout.application.git.GitCli;
import org.kitout.application.git.GitEnvironment;
import org.kitout.application.port.CommandRunner.Result;
import org.kitout.application.port.GitLabApi;
import org.kitout.application.port.SystemInfo;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.domain.clone.CloneTarget;
import org.kitout.domain.identity.Redactor;
import org.kitout.domain.identity.RemoteUrlParser;
import org.kitout.application.team.TeamDefaults;
import org.kitout.application.team.TeamScope;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Clones a GitLab group, a GitHub organization or selected repositories into the workspace.
 *
 * <p>Folders mirror the namespace under the workspace root. Clone URLs come from the API, never
 * built by hand. At most {@link #PARALLEL} clones run at once. A scan runs afterwards, so the new
 * services and their suggested group appear without manual input.
 */
@ApplicationScoped
public class CloneService {

    static final int PARALLEL = 4;
    static final long MIN_FREE_DISK_MB = 2048;

    public enum ItemStatus { QUEUED, CLONING, CLONED, SKIPPED, FAILED }

    /** A project of the group as the clone screen shows it. */
    public record Candidate(String name, String fullPath, String defaultBranch, Path target, boolean alreadyLocal) {
    }

    public record Selection(String fullPath, boolean light) {
    }

    public static final class Item {
        private final String fullPath;
        private final boolean light;
        private volatile ItemStatus status = ItemStatus.QUEUED;
        private volatile String message = "";

        Item(String fullPath, boolean light) {
            this.fullPath = fullPath;
            this.light = light;
        }

        public String fullPath() {
            return fullPath;
        }

        public boolean light() {
            return light;
        }

        public ItemStatus status() {
            return status;
        }

        public String message() {
            return message;
        }

        void set(ItemStatus s, String m) {
            status = s;
            message = m;
        }
    }

    public static final class Job {
        private final String id;
        private final List<Item> items;
        private volatile boolean finished;

        Job(String id, List<Item> items) {
            this.id = id;
            this.items = List.copyOf(items);
        }

        public String id() {
            return id;
        }

        public List<Item> items() {
            return items;
        }

        public boolean finished() {
            return finished;
        }
    }

    private final GitLabApi gitlab;
    private final CredentialService credentials;
    private final GitCli git;
    private final GitEnvironment gitEnv;
    private final ConfigRepository config;
    private final WorkspaceService workspace;
    private final SystemInfo system;
    private final KitoutPaths paths;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    /** Projects from the last listing, by full path: clone URLs always come from the API. */
    private final Map<String, GitLabApi.Project> listed = new ConcurrentHashMap<>();

    private final TeamDefaults team;
    private final org.kitout.application.port.GitHubApi github;

    public CloneService(GitLabApi gitlab, CredentialService credentials, GitCli git, GitEnvironment gitEnv,
                        ConfigRepository config, WorkspaceService workspace, SystemInfo system, KitoutPaths paths, TeamDefaults team) {
        this(gitlab, null, credentials, git, gitEnv, config, workspace, system, paths, team);
    }

    /** @param github lists a GitHub organization's or user's repositories; null: GitLab groups only */
    @jakarta.inject.Inject
    public CloneService(GitLabApi gitlab, org.kitout.application.port.GitHubApi github, CredentialService credentials, GitCli git,
                        GitEnvironment gitEnv, ConfigRepository config, WorkspaceService workspace, SystemInfo system, KitoutPaths paths,
                        TeamDefaults team) {
        this.github = github;
        this.team = team;
        this.gitlab = gitlab;
        this.credentials = credentials;
        this.git = git;
        this.gitEnv = gitEnv;
        this.config = config;
        this.workspace = workspace;
        this.system = system;
        this.paths = paths;
    }

    public List<Candidate> candidates(String groupPath) {
        GitLabSettings settings = requireGitLab();
        String group = groupPath.strip().replaceAll("^/+|/+$", "");
        List<GitLabApi.Project> found = switch (settings.kind()) {
            case GITLAB -> gitlab.groupProjects(settings.host(), group, credentials.token(settings.host()).orElseThrow(() -> new IllegalStateException(
                    "No GitLab token found. Store it once (see the doctor's credential checks), then try again.")));
            case GITHUB -> {
                if (github == null || group.contains("/")) {
                    throw new IllegalArgumentException("On GitHub, give an organization or a user name (e.g. acme), not a path");
                }
                yield github.ownerRepositories(settings.host(), group, credentials.token(settings.host()));
            }
            case GIT -> throw new IllegalStateException(settings.host() + " is a plain git server: kitout cannot list its repositories."
                    + " Name them in the team file (services, repositories), or clone them with git and add the folder in Settings."
                    + " Is it a GitLab or GitHub server? Then set that in Settings, Git");
        };
        Path root = paths.expand(settings.workspaceRoot());
        List<Candidate> result = new ArrayList<>();
        for (GitLabApi.Project p : found) {
            Path target;
            try {
                target = CloneTarget.resolve(root, p.fullPath());
            } catch (IllegalArgumentException e) {
                continue;   // unsafe path from the API: never offered
            }
            if (!TeamScope.allowsClone(team.current(), p.fullPath())) {
                continue;   // with a team file, only the repositories it names
            }
            listed.put(p.fullPath(), p);
            result.add(new Candidate(p.name(), p.fullPath(), p.defaultBranch(), target, isSameRepoAt(target, settings.host(), p.fullPath())));
        }
        return result;
    }

    /**
     * The repositories the team file names, as clone candidates, without asking the server for a
     * listing. Clone URLs are built from the configured git host, so a team file can only name
     * paths, never point kitout at another server. Works with any git server.
     */
    public List<Candidate> repositories(List<String> fullPaths) {
        GitLabSettings settings = requireGitLab();
        Path root = paths.expand(settings.workspaceRoot());
        List<Candidate> result = new ArrayList<>();
        for (String fullPath : fullPaths) {
            Path target;
            try {
                target = CloneTarget.resolve(root, fullPath);
            } catch (IllegalArgumentException e) {
                continue;
            }
            String name = fullPath.substring(fullPath.lastIndexOf('/') + 1);
            listed.putIfAbsent(fullPath, new GitLabApi.Project(name, fullPath, org.kitout.domain.git.GitHost.httpsUrl(settings.host(), fullPath),
                    org.kitout.domain.git.GitHost.sshUrl(settings.host(), fullPath), "", false));
            result.add(new Candidate(name, fullPath, "", target, isSameRepoAt(target, settings.host(), fullPath)));
        }
        return result;
    }

    /** Starts cloning in the background; follow it with {@link #job(String)}. */
    public Job clone(List<Selection> selection) {
        Job job = prepare(selection);
        Thread.ofVirtual().name("clone-" + job.id()).start(() -> execute(job));
        return job;
    }

    /** Clones and waits; used by {@code kitout clone}. */
    public Job cloneAndWait(List<Selection> selection) {
        Job job = prepare(selection);
        execute(job);
        return job;
    }

    public Job job(String id) {
        return Optional.ofNullable(jobs.get(id)).orElseThrow(() -> new NoSuchElementException("Unknown clone job '" + id + "'"));
    }

    private Job prepare(List<Selection> selection) {
        GitLabSettings settings = requireGitLab();
        if (selection == null || selection.isEmpty()) {
            throw new IllegalArgumentException("Select at least one project");
        }
        Path root = paths.expand(settings.workspaceRoot());
        OptionalLong free = system.freeDiskMb(root.getParent() != null && !Files.exists(root) ? root.getParent() : root);
        if (free.isPresent() && free.getAsLong() < MIN_FREE_DISK_MB) {
            throw new IllegalStateException("Only " + free.getAsLong() + " MB free disk space. Free some space, or use light clones.");
        }
        for (Selection s : selection) {   // validate all before starting any
            CloneTarget.resolve(root, s.fullPath());
            if (!TeamScope.allowsClone(team.current(), s.fullPath())) {
                throw new IllegalArgumentException(s.fullPath() + " is not in the team file; kitout clones only the team's repositories");
            }
            if (!listed.containsKey(s.fullPath())) {
                throw new IllegalArgumentException("Unknown project '" + s.fullPath() + "'. List the group's projects first.");
            }
        }
        List<Item> items = selection.stream().map(s -> new Item(s.fullPath(), s.light())).toList();
        Job job = new Job(UUID.randomUUID().toString(), items);
        jobs.put(job.id(), job);
        return job;
    }

    private void execute(Job job) {
        GitLabSettings settings = requireGitLab();
        Path root = paths.expand(settings.workspaceRoot());
        Semaphore slots = new Semaphore(PARALLEL);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Item item : job.items()) {
                pool.submit(() -> {
                    slots.acquireUninterruptibly();
                    try {
                        cloneOne(item, settings, root);
                    } finally {
                        slots.release();
                    }
                });
            }
        }
        if (job.items().stream().anyMatch(i -> i.status() == ItemStatus.CLONED)) {
            workspace.scan(List.of(settings.workspaceRoot()));
        }
        job.finished = true;
    }

    private void cloneOne(Item item, GitLabSettings settings, Path root) {
        Path target = CloneTarget.resolve(root, item.fullPath());
        if (Files.exists(target)) {
            if (isSameRepoAt(target, settings.host(), item.fullPath())) {
                item.set(ItemStatus.SKIPPED, "Already cloned at " + target);
            } else {
                item.set(ItemStatus.FAILED, target + " exists and is not this repository; move it away first");
            }
            return;
        }
        item.set(ItemStatus.CLONING, "Cloning into " + target);
        GitLabApi.Project project = listed.get(item.fullPath());
        String url = settings.cloneProtocol().equals("ssh") ? project.sshUrl() : project.httpUrl();
        boolean sameHost = RemoteUrlParser.parse(url).map(id -> id.host().equalsIgnoreCase(settings.host())).orElse(false);
        if (!sameHost || Redactor.hasEmbeddedCredentials(url)) {
            item.set(ItemStatus.FAILED, "The server returned a clone URL for another host; not cloned");
            return;
        }
        try {
            Files.createDirectories(target.getParent());
        } catch (java.io.IOException e) {
            item.set(ItemStatus.FAILED, "Cannot create " + target.getParent() + ": " + e.getMessage());
            return;
        }
        Result r = git.clone(url, target, item.light(), gitEnv.env(), gitEnv.removed());
        item.set(r.succeeded() ? ItemStatus.CLONED : ItemStatus.FAILED,
                r.succeeded() ? "Cloned into " + target : Redactor.redact(r.firstLine()));
    }

    private boolean isSameRepoAt(Path target, String host, String fullPath) {
        return Files.isDirectory(target.resolve(".git")) && git.originUrl(target)
                .flatMap(RemoteUrlParser::parse)
                .map(id -> id.host().equalsIgnoreCase(host) && id.fullPath().equalsIgnoreCase(fullPath))
                .orElse(false);
    }

    private GitLabSettings requireGitLab() {
        GitLabSettings settings = config.load().gitlab();
        if (!settings.isConfigured()) {
            throw new IllegalStateException("Set the git host in Settings, Git first");
        }
        return settings;
    }
}
