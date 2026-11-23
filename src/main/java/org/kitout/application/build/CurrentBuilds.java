package org.kitout.application.build;

import org.kitout.application.run.ArtifactLocator;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.domain.build.BuildMode;
import org.kitout.domain.build.BuildStatus;
import org.kitout.domain.project.Project;
import org.kitout.domain.run.RunMode;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * What can run now: the build output on disk, one per service and mode (the JVM app in
 * {@code target/quarkus-app}, the native runner). Every build overwrites it, so this is the only
 * build that exists; the attempts list is history. Removing a current build deletes the output.
 */
@ApplicationScoped
public class CurrentBuilds {

    /**
     * @param attemptId   the kitout build that produced it; empty when built outside kitout (IDE, terminal) or before kitout kept a list
     * @param usedBy      a reason it is in use (the service runs from it); empty if not
     */
    public record Current(String serviceId, String serviceName, BuildMode mode, Optional<String> version, Instant builtAt, long sizeBytes,
                          Path path, Optional<String> attemptId, Optional<String> usedBy) {

        public boolean removable() {
            return usedBy.isEmpty();
        }
    }

    public record Key(String serviceId, BuildMode mode) {
    }

    public record Removal(List<String> removed, List<String> kept) {
    }

    private final WorkspaceService workspace;
    private final ArtifactLocator artifacts;
    private final BuildService builds;
    private final org.kitout.application.config.ConfigRepository config;

    public CurrentBuilds(WorkspaceService workspace, ArtifactLocator artifacts, BuildService builds) {
        this(workspace, artifacts, builds, null);
    }

    /** @param config for the run mode: an IDE-mode service's build output belongs to the IDE */
    @jakarta.inject.Inject
    public CurrentBuilds(WorkspaceService workspace, ArtifactLocator artifacts, BuildService builds,
                         org.kitout.application.config.ConfigRepository config) {
        this.workspace = workspace;
        this.artifacts = artifacts;
        this.builds = builds;
        this.config = config;
    }

    /** @param runningMode the mode a service runs in right now, empty if it does not run */
    public List<Current> list(Function<String, Optional<RunMode>> runningMode) {
        List<Current> result = new ArrayList<>();
        for (Project p : workspace.projects()) {
            if (!p.type().buildable()) {
                continue;   // Node and image-only services: kitout builds nothing there
            }
            for (BuildMode mode : BuildMode.values()) {
                output(p, mode).ifPresent(path -> result.add(current(p, mode, path, runningMode)));
            }
        }
        result.sort(Comparator.comparing(Current::serviceName).thenComparing(Current::mode));
        return result;
    }

    /** Deletes the build output of these services and modes; output a running service uses, or a build is writing, stays. */
    public Removal remove(List<Key> keys, Function<String, Optional<RunMode>> runningMode) {
        List<String> removed = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (Key k : keys) {
            Project p = workspace.require(k.serviceId());
            Optional<Path> path = output(p, k.mode());
            if (path.isEmpty()) {
                continue;
            }
            Current c = current(p, k.mode(), path.get(), runningMode);
            if (!c.removable()) {
                kept.add(p.name() + " (" + label(k.mode()) + "): " + c.usedBy().get());
                continue;
            }
            try {
                deleteTree(path.get());
                removed.add(p.name() + " (" + label(k.mode()) + ")");
            } catch (IOException e) {
                kept.add(p.name() + " (" + label(k.mode()) + "): " + e.getMessage());
            }
        }
        return new Removal(removed, kept);
    }

    /**
     * For an attempt in the history: "replaced" when a later build of the same service and mode
     * overwrote its output, "removed" when its output is gone; empty when it is the current build.
     */
    public Optional<String> attemptNote(BuildJob job) {
        if (job.status() != BuildStatus.SUCCEEDED) {
            return Optional.empty();
        }
        if (!builds.newestSucceeded(job)) {
            return Optional.of("output replaced by a later build");
        }
        boolean onDisk = workspace.find(job.request().serviceId()).flatMap(p -> output(p, job.request().mode())).isPresent();
        return onDisk ? Optional.empty() : Optional.of("output removed or replaced outside kitout");
    }

    private Current current(Project p, BuildMode mode, Path path, Function<String, Optional<RunMode>> runningMode) {
        Instant builtAt = modified(path);
        Optional<String> attempt = builds.jobs().stream()
                .filter(j -> j.request().serviceId().equals(p.id()) && j.request().mode() == mode && j.status() == BuildStatus.SUCCEEDED)
                .filter(j -> j.finishedAt().map(f -> !f.plusSeconds(10).isBefore(builtAt)).orElse(false))
                .filter(builds::newestSucceeded).map(BuildJob::id).findFirst();
        Optional<String> usedBy = builds.active(p.id()).map(b -> "a build of " + p.name() + " is " + b.status().name().toLowerCase(java.util.Locale.ROOT))
                .or(() -> ideMode(p.id()) ? Optional.of(p.name() + " is in IDE mode: your IDE works with its build output. Change its run mode first")
                        : Optional.empty())
                .or(() -> runningMode.apply(p.id()).filter(m -> m == RunMode.valueOf(mode.name()))
                        .map(m -> p.name() + " runs from it: stop it first"));
        Path shown = mode == BuildMode.JVM ? path.getParent() : path;
        return new Current(p.id(), p.name(), mode, builtVersion(p).or(p::version), builtAt, size(shown), shown, attempt, usedBy);
    }

    /** Configured to run in the IDE: whether or not kitout sees it running, the IDE works in the project's target folder. */
    private boolean ideMode(String serviceId) {
        return config != null && config.load().serviceSettings(serviceId).runMode() == RunMode.IDE;
    }

    /** The JVM run jar's app folder, or the native runner. */
    private Optional<Path> output(Project p, BuildMode mode) {
        return mode == BuildMode.NATIVE ? artifacts.nativeRunner(p) : artifacts.jvmArtifact(p);
    }

    /** The version Maven wrote next to the output ({@code target/maven-archiver/pom.properties}), if any. */
    static Optional<String> builtVersion(Project p) {
        Path props = ArtifactLocator.outputDir(p).resolve("maven-archiver").resolve("pom.properties");
        try {
            if (Files.isRegularFile(props)) {
                java.util.Properties pr = new java.util.Properties();
                try (var in = Files.newBufferedReader(props)) {
                    pr.load(in);
                }
                return Optional.ofNullable(pr.getProperty("version"));
            }
        } catch (IOException e) {
            // unknown
        }
        return Optional.empty();
    }

    private static void deleteTree(Path path) throws IOException {
        Path target = path.getFileName().toString().equals("quarkus-run.jar") ? path.getParent() : path;
        if (Files.isDirectory(target)) {
            try (Stream<Path> walk = Files.walk(target)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        } else {
            Files.deleteIfExists(target);
        }
    }

    private static long size(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    return walk.filter(Files::isRegularFile).mapToLong(f -> {
                        try {
                            return Files.size(f);
                        } catch (IOException e) {
                            return 0;
                        }
                    }).sum();
                }
            }
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    private static Instant modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private static String label(BuildMode mode) {
        return mode == BuildMode.NATIVE ? "native" : "JVM";
    }
}
