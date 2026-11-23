package org.kitout.application.docker;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.ConfigRepository;
import org.kitout.application.docker.ComposeJob.Action;
import org.kitout.application.port.CommandRunner;
import org.kitout.application.port.CommandRunner.Command;
import org.kitout.application.port.CommandRunner.Result;
import org.kitout.application.port.ProcessControl;
import org.kitout.application.port.ProcessControl.ProcessRef;
import org.kitout.application.run.LogService;
import org.kitout.application.team.RepositoryLocator;
import org.kitout.application.team.TeamDefaults;
import org.kitout.application.workspace.FolderBrowser;
import org.kitout.domain.docker.ComposeFile;
import org.kitout.domain.docker.Container;
import org.kitout.domain.clone.CloneTarget;
import org.kitout.domain.identity.Redactor;
import org.kitout.domain.team.TeamSetup;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The containers the services need (databases, brokers, ...), started from the developer's
 * compose files: one {@code docker-compose-<environment>.yml} per environment, in the folders set
 * in Settings, Docker.
 *
 * <p>{@code up} and {@code down} run as jobs, like builds: the request returns at once, the output
 * goes to a log file the UI follows live. One job per environment at a time. kitout never deletes
 * volumes: {@code down} stops and removes the containers, the data stays.
 *
 * <p>Which containers belong to an environment comes from compose's own label
 * {@code com.docker.compose.project.config_files}, so a custom project name in the file or in
 * {@code .env} does not matter.
 */
@ApplicationScoped
public class ComposeService {

    static final int KEEP_JOBS = 30;
    private static final DateTimeFormatter JOB_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * An environment as kitout knows it; {@code id} is unique even if two files have the same name.
     *
     * @param picked the developer picked this file by hand (it can be forgotten again); false if found in a folder
     * @param downloaded a team environment whose repository is not cloned: its files were read from the git server
     */
    public record Environment(String id, String name, Path file, boolean picked, List<Path> overrides, List<String> profiles,
                              boolean team, String description, List<String> missing, boolean autoStart, boolean downloaded) {

        public Environment {
            overrides = List.copyOf(overrides);
            profiles = List.copyOf(profiles);
            missing = List.copyOf(missing);
        }

        public Environment(String id, String name, Path file, boolean picked, List<Path> overrides, List<String> profiles,
                           boolean team, String description, List<String> missing, boolean autoStart) {
            this(id, name, file, picked, overrides, profiles, team, description, missing, autoStart, false);
        }

        public Environment(String id, String name, Path file, boolean picked, List<Path> overrides, List<String> profiles,
                           boolean team, String description, List<String> missing) {
            this(id, name, file, picked, overrides, profiles, team, description, missing, false);
        }

        public Environment(String id, String name, Path file, boolean picked) {
            this(id, name, file, picked, List.of(), List.of(), false, null, List.of(), false);
        }

        /** All files, in the order {@code docker compose -f} gets them. */
        public List<Path> files() {
            List<Path> all = new ArrayList<>();
            all.add(file);
            all.addAll(overrides);
            return all;
        }
    }

    /** One entry of the compose file picker: a YAML file and whether it is a compose file. */
    public record Candidate(String name, String path, boolean compose, String environment, boolean picked) {
    }

    static final long MAX_COMPOSE_BYTES = 1024 * 1024;

    /**
     * What {@code docker ps} said.
     *
     * @param problem why Docker could not be asked, e.g. "Cannot connect to the Docker daemon"; null if fine
     */
    public record Containers(boolean available, String problem, List<Container> list) {
    }

    private final ConfigRepository config;
    private final KitoutPaths paths;
    private final CommandRunner runner;
    private final ProcessControl processes;
    private final LogService logs;
    private final FolderBrowser browser;
    private final Map<String, ComposeJob> jobs = new LinkedHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    private final TeamDefaults team;
    private final RepositoryLocator locator;
    private final TeamComposeDownloads downloads;

    public ComposeService(ConfigRepository config, KitoutPaths paths, CommandRunner runner, ProcessControl processes, LogService logs,
                          FolderBrowser browser, TeamDefaults team) {
        this(config, paths, runner, processes, logs, browser, team, null, null);
    }

    public ComposeService(ConfigRepository config, KitoutPaths paths, CommandRunner runner, ProcessControl processes, LogService logs,
                          FolderBrowser browser, TeamDefaults team, RepositoryLocator locator) {
        this(config, paths, runner, processes, logs, browser, team, locator, null);
    }

    /** @param downloads reads the team's compose files from their repository when their repository is not cloned; null: only clones count */
    @jakarta.inject.Inject
    public ComposeService(ConfigRepository config, KitoutPaths paths, CommandRunner runner, ProcessControl processes, LogService logs,
                          FolderBrowser browser, TeamDefaults team, RepositoryLocator locator, TeamComposeDownloads downloads) {
        this.downloads = downloads;
        this.locator = locator;
        this.team = team;
        this.browser = browser;
        this.config = config;
        this.paths = paths;
        this.runner = runner;
        this.processes = processes;
        this.logs = logs;
    }

    /** The configured folders, as typed (with {@code ~}), and whether each exists. */
    public Map<String, Boolean> folders() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (String dir : config.load().docker().composeDirs()) {
            result.put(dir, Files.isDirectory(paths.expand(dir)));
        }
        return result;
    }

    /** The picked files, and whether each still exists. */
    public Map<String, Boolean> pickedFiles() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (String file : config.load().docker().composeFiles()) {
            result.put(file, Files.isRegularFile(Path.of(file)));
        }
        return result;
    }

    /**
     * Every compose environment: the convention-named files in the configured folders (not in
     * their subfolders), plus the files picked by hand. Sorted by id; a file found both ways counts once, as picked.
     */
    public List<Environment> environments() {
        List<ComposeFile> files = new ArrayList<>();
        java.util.Set<Path> picked = new java.util.LinkedHashSet<>();
        for (String f : config.load().docker().composeFiles()) {
            Path file = Path.of(f);
            if (Files.isRegularFile(file)) {
                picked.add(file);
                files.add(new ComposeFile(ComposeFile.nameForPicked(file.getFileName().toString()), file));
            }
        }
        for (String dir : config.load().docker().composeDirs()) {
            Path folder = paths.expand(dir).toAbsolutePath().normalize();
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (Stream<Path> entries = Files.list(folder)) {
                entries.filter(Files::isRegularFile)
                        .filter(f -> !picked.contains(f))
                        .forEach(f -> ComposeFile.environmentOf(f.getFileName().toString())
                                .ifPresent(env -> files.add(new ComposeFile(env, f))));
            } catch (IOException e) {
                // an unreadable folder shows no environments; folders() still lists it
            }
        }
        List<Environment> fromTeam = teamEnvironments();
        List<Environment> result = new ArrayList<>(fromTeam);
        result.addAll(withUniqueIds(files, picked, fromTeam.stream().map(Environment::id).collect(java.util.stream.Collectors.toSet())));
        result.sort(Comparator.comparing(Environment::id));
        return List.copyOf(result);
    }

    /**
     * The team file's environments. Files are named by repository and path, so they resolve under
     * this developer's workspace root. A file whose repository is not cloned is read from its repository
     * (see {@link TeamComposeDownloads}); if that fails too, it is listed as missing.
     */
    public List<Environment> teamEnvironments() {
        Optional<TeamSetup> setup = team.current();
        if (setup.isEmpty() || setup.get().compose().isEmpty()) {
            return List.of();
        }
        Path root = paths.expand(config.load().gitlab().workspaceRoot()).toAbsolutePath().normalize();
        List<Environment> result = new ArrayList<>();
        for (TeamSetup.ComposeEnvironment env : setup.get().compose()) {
            List<Path> files = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            boolean downloaded = false;
            for (TeamSetup.FileRef ref : env.files()) {
                if (ref.local()) {
                    Path file = paths.expand(ref.file()).toAbsolutePath().normalize();
                    if (!file.startsWith(paths.home())) {
                        missing.add(ref.file() + " is outside your home folder; kitout only runs compose files below it");
                        continue;
                    }
                    files.add(file);
                    if (!Files.isRegularFile(file)) {
                        missing.add(ref.file() + " does not exist on this machine");
                    }
                    continue;
                }
                Path repo;
                try {
                    // where kitout clones it, or wherever it was cloned by hand (found by its git origin)
                    repo = locator != null ? locator.locate(ref.repo()).orElse(locator.cloneTarget(ref.repo())) : CloneTarget.resolve(root, ref.repo());
                } catch (IllegalArgumentException e) {
                    missing.add(ref.repo() + ": " + e.getMessage());
                    continue;
                }
                Path file = repo.resolve(ref.path()).normalize();
                if (!file.startsWith(repo)) {
                    missing.add(ref.repo() + "/" + ref.path() + " (leaves the repository)");
                    continue;
                }
                if (!Files.isDirectory(repo) && downloads != null) {
                    TeamComposeDownloads.Download copy = downloads.get(ref);
                    files.add(copy.file() != null ? copy.file() : file);
                    downloaded |= copy.file() != null;
                    if (copy.problem() != null) {
                        missing.add(copy.problem());
                    }
                    continue;
                }
                files.add(file);
                if (!Files.isDirectory(repo)) {
                    missing.add(ref.repo() + " is not cloned (expected at " + repo + ")");
                } else if (!Files.isRegularFile(file)) {
                    missing.add(ref.path() + " is not in " + ref.repo() + " (looked for " + file + "): check \"path\" in the team file");
                }
            }
            if (files.isEmpty()) {
                continue;
            }
            result.add(new Environment(env.name(), env.name(), files.get(0), false, files.subList(1, files.size()),
                    env.profiles() == null ? List.of() : env.profiles(), true, env.description(), missing, env.autoStart(), downloaded));
        }
        return result;
    }

    /**
     * Brings up the team's compose environments marked {@code autoStart} whose containers do not run
     * yet, and waits until {@code docker compose up -d} is done. Called on kitout's start and before a
     * service starts, so the databases are there when the services need them. Cheap when all runs.
     *
     * @return one line per environment started, for the service log
     * @throws IllegalStateException when an environment cannot be started (files missing, compose failed)
     */
    public synchronized List<String> ensureTeamEnvironments() {
        List<Environment> wanted = teamEnvironments().stream().filter(Environment::autoStart).toList();
        if (wanted.isEmpty()) {
            return List.of();
        }
        List<String> done = new ArrayList<>();
        Containers all = null;
        for (Environment env : wanted) {
            if (!env.missing().isEmpty()) {
                throw new IllegalStateException("The team's compose environment " + env.id() + " cannot start: " + String.join("; ", env.missing())
                        + ". Clone it on the Docker page (Team repositories) or in Setup");
            }
            if (all == null) {
                all = containers(true);
                if (!all.available()) {
                    throw new IllegalStateException("Docker is not available (" + all.problem() + "), so the compose environment "
                            + env.id() + " cannot start");
                }
            }
            String file = env.file().toString();
            boolean running = all.list().stream().anyMatch(c -> c.createdFrom(file) && c.running());
            boolean anyStopped = all.list().stream().anyMatch(c -> c.createdFrom(file) && !c.running());
            if (running && !anyStopped) {
                continue;
            }
            ComposeJob job = latestJob(env.id()).filter(j -> j.status() == ComposeJob.Status.RUNNING).orElseGet(() -> start(env.id(), Action.UP));
            waitFor(job, Duration.ofMinutes(10));
            if (job.status() != ComposeJob.Status.SUCCEEDED) {
                throw new IllegalStateException("docker compose up for " + env.id() + " failed" + job.exitCode().map(c -> " (exit " + c + ")").orElse("")
                        + ". See its output on the Docker page");
            }
            done.add("Started the team's compose environment " + env.id() + " (docker compose up -d)");
        }
        return done;
    }

    private static void waitFor(ComposeJob job, Duration max) {
        java.time.Instant until = java.time.Instant.now().plus(max);
        while (job.status() == ComposeJob.Status.RUNNING && java.time.Instant.now().isBefore(until)) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    static List<Environment> withUniqueIds(List<ComposeFile> files, java.util.Set<Path> picked) {
        return withUniqueIds(files, picked, java.util.Set.of());
    }

    /** @param taken ids already used (by the team's environments): a file with that name gets its folder appended */
    static List<Environment> withUniqueIds(List<ComposeFile> files, java.util.Set<Path> picked, java.util.Set<String> taken) {
        Map<String, Long> counts = new HashMap<>();
        files.forEach(f -> counts.merge(f.environment(), 1L, Long::sum));
        taken.forEach(t -> counts.merge(t, 1L, Long::sum));
        return files.stream()
                .map(f -> new Environment(counts.get(f.environment()) > 1
                        ? f.environment() + "@" + f.folder().getFileName()
                        : f.environment(), f.environment(), f.file(), picked.contains(f.file())))
                .sorted(Comparator.comparing(Environment::id))
                .toList();
    }

    public Environment require(String id) {
        return environments().stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("No compose environment '" + id
                        + "'. kitout looks for docker-compose-<environment>.yml in the folders set in Settings, Docker"));
    }

    /**
     * The compose file picker: folders (as the folder picker shows them) and the YAML files in
     * {@code folder}, each marked whether it is a compose file. Reads at most 1 MB per file.
     */
    public List<Candidate> candidates(String folder) {
        java.util.Set<String> pickedNow = new java.util.HashSet<>(config.load().docker().composeFiles());
        return browser.files(folder, ComposeFile::isYaml).stream()
                .map(f -> {
                    boolean compose = isCompose(Path.of(f.path()));
                    return new Candidate(f.name(), f.path(), compose, compose ? ComposeFile.nameForPicked(f.name()) : null,
                            pickedNow.contains(f.path()));
                })
                .toList();
    }

    /**
     * Adds a compose file the developer chose. It must be inside the home folder, a YAML file,
     * and a compose file; it is stored with its real path (symbolic links resolved).
     */
    public Environment pick(String path) {
        Path file = browser.resolveFile(path);
        if (!ComposeFile.isYaml(file.getFileName().toString()) || !isCompose(file)) {
            throw new IllegalArgumentException(file.getFileName() + " is not a compose file: it has no top-level 'services:'");
        }
        String stored = file.toString();
        config.update(c -> c.docker().composeFiles().contains(stored) ? c
                : c.withDocker(c.docker().withComposeFiles(append(c.docker().composeFiles(), stored))));
        return environments().stream().filter(e -> e.file().equals(file)).findFirst()
                .orElseThrow(() -> new IllegalStateException("The file was added but is not readable: " + stored));
    }

    /** Forgets a picked file. The file itself stays on disk. */
    public void forget(String path) {
        if (!config.load().docker().composeFiles().contains(path)) {
            throw new NoSuchElementException("'" + path + "' is not a picked compose file");
        }
        config.update(c -> c.withDocker(c.docker().withComposeFiles(
                c.docker().composeFiles().stream().filter(f -> !f.equals(path)).toList())));
    }

    private static List<String> append(List<String> list, String value) {
        List<String> copy = new ArrayList<>(list);
        copy.add(value);
        return copy;
    }

    static boolean isCompose(Path file) {
        try {
            if (Files.size(file) > MAX_COMPOSE_BYTES) {
                return false;
            }
            return ComposeFile.looksLikeCompose(Files.readString(file));
        } catch (IOException | java.io.UncheckedIOException e) {
            return false;
        }
    }

    /** All containers on this machine (running only, or all with {@code includeStopped}). */
    public Containers containers(boolean includeStopped) {
        List<String> args = new ArrayList<>(List.of("docker", "ps", "--no-trunc", "--format", Container.FORMAT));
        if (includeStopped) {
            args.add(2, "-a");
        }
        Result r = runner.run(Command.of(args).withTimeout(Duration.ofSeconds(15)));
        if (!r.succeeded()) {
            String why = r.notFound() ? "The docker command was not found; install Docker" : Redactor.redact(r.firstLine());
            return new Containers(false, why, List.of());
        }
        List<Container> list = r.output().lines().map(Container::parse).flatMap(Optional::stream)
                .sorted(Comparator.comparing(Container::name)).toList();
        return new Containers(true, null, list);
    }

    /** Starts {@code docker compose -f <file> up -d} or {@code down} in the file's folder. */
    public synchronized ComposeJob start(String environmentId, Action action) {
        Environment env = require(environmentId);
        if (!env.missing().isEmpty()) {
            throw new IllegalStateException("The team's environment " + env.id() + " needs files that are not on this machine: "
                    + String.join(", ", env.missing()) + ". Clone the team's repositories first (Settings, Team setup, or: kitout team clone)");
        }
        latestJob(env.id()).filter(j -> j.status() == ComposeJob.Status.RUNNING).ifPresent(j -> {
            throw new IllegalStateException("docker compose " + j.action().name().toLowerCase(java.util.Locale.ROOT) + " for " + env.id()
                    + " is still running; wait for it to finish");
        });
        String id = LocalDateTime.now().format(JOB_ID) + "-" + sequence.incrementAndGet();
        Path logFile = paths.stateDir().resolve("compose").resolve(id + ".log");
        ComposeJob job = new ComposeJob(id, env.id(), action, logFile);
        List<String> command = command(env, action);
        logs.mark(logFile, String.join(" ", command));
        ProcessRef ref;
        try {
            // plain output for the log window: no colour codes, no cursor tricks
            ref = processes.spawn(command, env.file().getParent(), Map.of("COMPOSE_ANSI", "never", "NO_COLOR", "1"), logFile);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not run docker compose: " + e.getMessage(), e);
        }
        job.started(ref);
        remember(job);
        Thread.ofVirtual().name("compose-" + id).start(() -> watch(job, ref));
        return job;
    }

    static List<String> command(Path file, Action action) {
        return command(new Environment("", "", file, false), action);
    }

    /** {@code docker compose -f a.yml [-f b.yml ...] [--profile p ...] up -d | down}: later files override earlier ones. */
    static List<String> command(Environment env, Action action) {
        List<String> cmd = new ArrayList<>(List.of("docker", "compose"));
        env.files().forEach(f -> cmd.addAll(List.of("-f", f.toString())));
        env.profiles().forEach(p -> cmd.addAll(List.of("--profile", p)));
        if (action == Action.UP) {
            cmd.addAll(List.of("up", "-d"));
        } else {
            cmd.add("down");
        }
        return cmd;
    }

    private void watch(ComposeJob job, ProcessRef ref) {
        while (processes.isAlive(ref)) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        int exit = processes.exitCode(ref).orElse(-1);
        job.finished(exit);
        logs.mark(job.logFile(), exit == 0 ? "done" : "failed with exit code " + exit);
    }

    public Optional<ComposeJob> job(String id) {
        synchronized (jobs) {
            return Optional.ofNullable(jobs.get(id));
        }
    }

    public ComposeJob requireJob(String id) {
        return job(id).orElseThrow(() -> new NoSuchElementException("Unknown compose job '" + id + "'"));
    }

    public Optional<ComposeJob> latestJob(String environmentId) {
        synchronized (jobs) {
            return jobs.values().stream().filter(j -> j.environmentId().equals(environmentId)).reduce((a, b) -> b);
        }
    }

    private void remember(ComposeJob job) {
        synchronized (jobs) {
            jobs.put(job.id(), job);
            while (jobs.size() > KEEP_JOBS) {
                jobs.remove(jobs.keySet().iterator().next());
            }
        }
    }
}
