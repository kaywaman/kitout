package org.kitout.application.build;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.ConfigRepository;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.env.EnvironmentService;
import org.kitout.application.port.ProcessControl;
import org.kitout.application.port.ProcessControl.ProcessRef;
import org.kitout.application.port.SystemInfo;
import org.kitout.application.run.LogService;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.domain.build.BuildCommandFactory;
import org.kitout.domain.build.BuildMode;
import org.kitout.domain.build.BuildRequest;
import org.kitout.domain.build.BuildStatus;
import org.kitout.domain.build.NativeBuildErrorExplainer;
import org.kitout.domain.build.NativeBuildErrorExplainer.Explanation;
import org.kitout.domain.project.Project;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local builds with profile, mode and skip-tests.
 *
 * <p>Native builds run one at a time: a fair in-process permit gives each queued build its
 * position, and a file lock extends the rule to every kitout process (UI and CLI). JVM builds are
 * not limited. Every build runs in its own process group, so cancelling stops the whole tree.
 */
@ApplicationScoped
public class BuildService {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(BuildService.class);

    static final Duration POLL = Duration.ofMillis(500);
    static final int KEEP_PER_SERVICE = 10;

    private final WorkspaceService workspace;
    private final ConfigRepository config;
    private final ProcessControl processes;
    private final SystemInfo system;
    private final LogService logs;
    private final KitoutPaths paths;
    private final Semaphore nativePermit = new Semaphore(1, true);
    private final Map<String, BuildJob> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    private final BuildHistory history;
    private final EnvironmentService environment;
    private volatile boolean loaded;

    public BuildService(WorkspaceService workspace, ConfigRepository config, ProcessControl processes,
                        SystemInfo system, LogService logs, KitoutPaths paths) {
        this(workspace, config, processes, system, logs, paths, null, null);
    }

    public BuildService(WorkspaceService workspace, ConfigRepository config, ProcessControl processes,
                        SystemInfo system, LogService logs, KitoutPaths paths, BuildHistory history) {
        this(workspace, config, processes, system, logs, paths, history, null);
    }

    /**
     * @param history     keeps the build attempts across restarts; null in unit tests
     * @param environment the variables for builds (shared and per service); null: none, in unit tests
     */
    @jakarta.inject.Inject
    public BuildService(WorkspaceService workspace, ConfigRepository config, ProcessControl processes,
                        SystemInfo system, LogService logs, KitoutPaths paths, BuildHistory history,
                        EnvironmentService environment) {
        this.environment = environment;
        this.history = history;
        this.workspace = workspace;
        this.config = config;
        this.processes = processes;
        this.system = system;
        this.logs = logs;
        this.paths = paths;
    }

    /** Queues a build and returns at once. */
    public BuildJob submit(BuildRequest request) {
        BuildJob job = create(request);
        Thread.ofVirtual().name("build-" + job.id()).start(() -> execute(job));
        return job;
    }

    /** The result of building several services at once. {@code skipped} says why, one sentence each. */
    public record Batch(List<BuildJob> started, List<String> skipped) {
    }

    /**
     * Builds several services with the same options. JVM builds all start at once; native builds
     * queue and run one at a time, as always. A service that is already building is skipped.
     */
    public Batch submitAll(List<String> serviceIds, String profile, BuildMode mode, boolean skipTests) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one service to build");
        }
        List<BuildJob> started = new java.util.ArrayList<>();
        List<String> skipped = new java.util.ArrayList<>();
        for (String id : serviceIds) {
            Project project = workspace.require(id);
            Optional<BuildJob> active = active(id);
            if (active.isPresent()) {
                skipped.add(project.name() + " is already building (" + active.get().status().name().toLowerCase(java.util.Locale.ROOT) + ")");
                continue;
            }
            if (!project.type().buildable()) {
                skipped.add(project.name() + " is a " + project.type().label() + " service: kitout does not build it");
                continue;
            }
            // a selection with Quarkus and other Java projects: native for the Quarkus ones, JVM for the rest
            BuildMode own = mode == BuildMode.NATIVE && !project.type().nativeBuild() ? BuildMode.JVM : mode;
            if (own != mode) {
                skipped.add(project.name() + " is built as JVM: native builds are for Quarkus for now");
            }
            started.add(submit(new BuildRequest(id, profile, own, skipTests)));
        }
        return new Batch(started, skipped);
    }

    /** A queued or running build of the service, if any. */
    public Optional<BuildJob> active(String serviceId) {
        return jobs.values().stream()
                .filter(j -> j.request().serviceId().equals(serviceId))
                .filter(j -> j.status() == BuildStatus.QUEUED || j.status() == BuildStatus.RUNNING)
                .findFirst();
    }

    /** Cancels every queued or running build and forgets the history (for a reset). Returns how many builds were known. */
    public int clearHistory() {
        int count = jobs.size();
        for (BuildJob job : jobs.values()) {
            if (job.status() == BuildStatus.QUEUED || job.status() == BuildStatus.RUNNING) {
                cancel(job.id());
            }
        }
        jobs.clear();
        save();
        return count;
    }

    /**
     * @param removed ids taken off the list (and their logs deleted)
     * @param kept    one sentence per build that stayed, with the reason
     */
    public record Removal(List<String> removed, List<String> kept) {
    }

    /** Why a build attempt cannot be removed right now (it is queued or running); empty if it can. */
    public Optional<String> whyNotRemovable(BuildJob job) {
        return job.status().isFinished() ? Optional.empty()
                : Optional.of("still " + job.status().name().toLowerCase(java.util.Locale.ROOT) + ": cancel it first");
    }

    /**
     * The newest succeeded attempt of this service and mode: its output is the current build (if
     * still on disk). Older succeeded attempts were replaced by it.
     */
    public boolean newestSucceeded(BuildJob job) {
        return job.status() == BuildStatus.SUCCEEDED && jobs.values().stream()
                .filter(j -> j.request().serviceId().equals(job.request().serviceId()) && j.request().mode() == job.request().mode())
                .filter(j -> j.status() == BuildStatus.SUCCEEDED)
                .max(Comparator.comparing(j -> j.finishedAt().orElse(j.createdAt()))).map(j -> j.id().equals(job.id())).orElse(false);
    }

    /** For tests: a build as if it had been submitted. */
    void remember(BuildJob job) {
        jobs.put(job.id(), job);
    }

    /** Takes finished builds off the list and deletes their logs; the projects' build output is not touched. */
    public synchronized Removal remove(List<String> ids) {
        List<String> removed = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (String id : ids == null ? List.<String>of() : ids) {
            BuildJob job = jobs.get(id);
            if (job == null) {
                continue;
            }
            Optional<String> why = whyNotRemovable(job);
            if (why.isPresent()) {
                kept.add(job.serviceName() + " " + id + ": " + why.get());
                continue;
            }
            jobs.remove(id);
            try {
                if (job.logFile() != null) {
                    java.nio.file.Files.deleteIfExists(job.logFile());
                }
            } catch (java.io.IOException e) {
                // the log stays; the build is gone from the list
            }
            removed.add(id);
        }
        if (!removed.isEmpty()) {
            save();
        }
        return new Removal(removed, kept);
    }

    // ---- history and build output found on disk ----------------------------------------------------

    private void ensureLoaded() {
        if (loaded || history == null) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            BuildHistory.Stored stored = history.load();
            stored.jobs().stream().filter(j -> !j.discovered()).forEach(j -> jobs.putIfAbsent(j.id(), j));
            loaded = true;
            trimFinished();
        }
    }

    private void save() {
        if (history != null) {
            history.save(jobs.values().stream().filter(j -> j.status().isFinished())
                    .sorted(Comparator.comparing(BuildJob::createdAt).reversed()).toList(), Map.of());
        }
    }

    /** Builds and waits; used by {@code kitout build}. */
    public BuildJob runAndWait(BuildRequest request) {
        BuildJob job = create(request);
        execute(job);
        return job;
    }

    public List<BuildJob> jobs() {
        ensureLoaded();
        return jobs.values().stream().sorted(Comparator.comparing(BuildJob::createdAt).reversed()).toList();
    }

    public BuildJob job(String id) {
        ensureLoaded();
        return Optional.ofNullable(jobs.get(id)).orElseThrow(() -> new NoSuchElementException("Unknown build '" + id + "'"));
    }

    /** 1 for the next native build to run, 0 if not queued for the native permit. */
    public int queuePosition(BuildJob job) {
        if (job.status() != BuildStatus.QUEUED || job.request().mode() != BuildMode.NATIVE) {
            return 0;
        }
        List<BuildJob> queued = jobs().stream()
                .filter(j -> j.status() == BuildStatus.QUEUED && j.request().mode() == BuildMode.NATIVE)
                .sorted(Comparator.comparing(BuildJob::createdAt))
                .toList();
        return queued.indexOf(job) + 1;
    }

    public void cancel(String id) {
        BuildJob job = job(id);
        job.requestCancel();
        job.process().ifPresent(ref -> processes.terminate(ref, Duration.ofSeconds(10)));
    }

    private BuildJob create(BuildRequest request) {
        Project project = workspace.require(request.serviceId());
        if (!project.type().buildable()) {
            throw new IllegalStateException(project.name() + " is a " + project.type().label() + " service: kitout does not build it"
                    + (project.type() == org.kitout.domain.project.ProjectType.NODE ? " (it starts with its npm script, which builds what it needs)" : ""));
        }
        if (request.mode() == BuildMode.NATIVE && !project.type().nativeBuild()) {
            throw new IllegalStateException(project.name() + " is a " + project.type().label()
                    + " project: native builds are for Quarkus for now. Build it as JVM");
        }
        String id = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + sequence.incrementAndGet();
        ensureLoaded();
        BuildJob job = new BuildJob(id, project.name(), request, paths.buildLog(id), project.version().orElse(null));
        job.onFinish(this::save);
        jobs.put(id, job);
        trimFinished();
        return job;
    }

    private void execute(BuildJob job) {
        Project project = workspace.require(job.request().serviceId());
        KitoutConfig cfg = config.load();
        boolean isNative = job.request().mode() == BuildMode.NATIVE;
        logs.mark(job.logFile(), "build " + job.request().mode() + " profile=" + job.request().profile()
                + (job.request().skipTests() ? " skip-tests" : ""));
        if (!isNative) {
            run(job, project, cfg);
            return;
        }
        try {
            job.waiting("an earlier native build in this window");
            nativePermit.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            job.finish(BuildStatus.CANCELLED, null);
            return;
        }
        try (FileChannel channel = openLockFile();
             FileLock lock = acquireCrossProcessLock(channel, job)) {
            if (lock == null) {
                job.finish(BuildStatus.CANCELLED, null);
                return;
            }
            OptionalLong free = system.availableMemoryMb();
            int needed = cfg.build().nativeMinFreeMemoryMb();
            if (free.isPresent() && free.getAsLong() < needed) {
                job.finish(BuildStatus.FAILED, new Explanation("Only " + free.getAsLong() + " MB of memory is free; a native build needs about "
                        + needed + " MB.", "Stop some services or close applications, then build again."));
                return;
            }
            run(job, project, cfg);
        } catch (IOException e) {
            job.finish(BuildStatus.FAILED, new Explanation("Could not take the native build lock: " + e.getMessage(),
                    "Check that " + paths.stateDir() + " is writable."));
        } finally {
            nativePermit.release();
        }
    }

    private FileChannel openLockFile() throws IOException {
        Files.createDirectories(paths.nativeBuildLock().getParent());
        return FileChannel.open(paths.nativeBuildLock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    private FileLock acquireCrossProcessLock(FileChannel channel, BuildJob job) throws IOException {
        while (!job.cancelRequested()) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException e) {
                // held inside this JVM: cannot happen while we hold the permit, but be safe
            }
            job.waiting("a native build in another kitout window");
            sleep();
        }
        return null;
    }

    private void run(BuildJob job, Project project, KitoutConfig cfg) {
        if (job.cancelRequested()) {
            job.finish(BuildStatus.CANCELLED, null);
            return;
        }
        List<String> command = BuildCommandFactory.command(project.type(), project.buildTool(), job.request(), cfg.build().nativeContainerBuild());
        Map<String, String> env;
        try {
            env = environment == null ? Map.of() : environment.resolveBuild(project.id());
        } catch (IllegalStateException e) {   // a secret for builds is missing from the keyring
            job.finish(BuildStatus.FAILED, new Explanation(e.getMessage(), "Enter it again under Environment (shared, or in the service's row)."));
            return;
        }
        ProcessRef ref = processes.spawn(command, project.path(), env, job.logFile());
        job.running(ref);
        while (processes.isAlive(ref)) {
            sleep();
        }
        int exit = processes.exitCode(ref).orElse(-1);
        if (job.cancelRequested()) {
            job.finish(BuildStatus.CANCELLED, null);
        } else if (exit == 0) {
            job.finish(BuildStatus.SUCCEEDED, null);
        } else {
            String tail = String.join("\n", logs.tail(job.logFile(), 400));
            job.finish(BuildStatus.FAILED, NativeBuildErrorExplainer.explain(tail).orElse(null));
        }
    }

    /** Keeps the newest {@value #KEEP_PER_SERVICE} finished attempts of each service; older ones go with their logs. */
    private void trimFinished() {
        Map<String, List<BuildJob>> byService = new java.util.HashMap<>();
        jobs.values().stream().filter(j -> j.status().isFinished())
                .sorted(Comparator.comparing(BuildJob::createdAt).reversed())
                .forEach(j -> byService.computeIfAbsent(j.request().serviceId(), k -> new ArrayList<>()).add(j));
        boolean changed = false;
        for (List<BuildJob> list : byService.values()) {
            for (BuildJob old : list.subList(Math.min(KEEP_PER_SERVICE, list.size()), list.size())) {
                jobs.remove(old.id());
                deleteLog(old);
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    private static void deleteLog(BuildJob job) {
        try {
            if (job.logFile() != null) {
                java.nio.file.Files.deleteIfExists(job.logFile());
            }
        } catch (IOException e) {
            // the log stays
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
