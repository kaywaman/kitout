package org.kitout.application.workspace;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.ConfigRepository;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.config.KitoutConfig.ServiceSettings;
import org.kitout.domain.project.Project;
import org.kitout.application.team.TeamDefaults;
import org.kitout.application.team.TeamScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * The services kitout knows about: projects on disk (Quarkus, Spring Boot, Java, Node) and the team's image-only services.
 *
 * <p>A scan walks the workspace roots and records each project's path in the configuration.
 * Everything else (ports, URLs, identity) is re-read from the repository when needed, so it is
 * never out of date with the files on disk.
 */
@ApplicationScoped
public class WorkspaceService {

    /** A scan does not descend further than this below a root: repos live at most a few levels deep. */
    static final int MAX_DEPTH = 6;
    private static final Set<String> SKIP = Set.of("node_modules", "target", "build", ".git", ".idea", ".vscode",
            ".gradle", ".mvn", "src", "dist", ".quinoa", "vendor", ".cache");

    /** @param outsideTeam folders of Quarkus projects the team file does not name; not added */
    public record ScanResult(List<Project> found, List<String> added, List<String> removed, List<String> outsideTeam) {

        public ScanResult(List<Project> found, List<String> added, List<String> removed) {
            this(found, added, removed, List.of());
        }
    }

    private final ProjectReader reader;
    private final ConfigRepository config;
    private final KitoutPaths paths;
    private final TeamDefaults team;

    public WorkspaceService(ProjectReader reader, ConfigRepository config, KitoutPaths paths) {
        this(reader, config, paths, TeamDefaults.NONE);
    }

    /** With a team file, only the services it names are loaded (see {@link TeamScope}). */
    @Inject
    public WorkspaceService(ProjectReader reader, ConfigRepository config, KitoutPaths paths, TeamDefaults team) {
        this.reader = reader;
        this.config = config;
        this.paths = paths;
        this.team = team;
    }

    private boolean inScope(Project p) {
        return TeamScope.allows(team.current(), p.identity());
    }

    /** Scans the given roots (or the configured ones), remembers them and records every project found. */
    public ScanResult scan(List<String> roots) {
        List<String> effectiveRoots = roots == null || roots.isEmpty() ? config.load().workspaceRoots() : roots;
        KitoutConfig before = config.load();
        List<Project> found = new ArrayList<>();
        List<String> outsideTeam = new ArrayList<>();
        for (String root : effectiveRoots) {
            for (Project p : scanRoot(paths.expand(root))) {
                if (before.isHidden(p.path().toString())) {
                    continue;
                }
                if (inScope(p)) {
                    found.add(p);
                } else {
                    outsideTeam.add(p.path().toString());
                }
            }
        }
        found.sort(Comparator.comparing(Project::id));

        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        config.update(current -> {
            Map<String, ServiceSettings> services = new LinkedHashMap<>();
            for (Project p : found) {
                ServiceSettings existing = current.services().get(p.id());
                if (existing == null) {
                    added.add(p.id());
                    services.put(p.id(), ServiceSettings.defaults(p.path().toString()));
                } else {
                    services.put(p.id(), existing.withPath(p.path().toString()));
                }
            }
            // Keep settings of projects outside the scanned roots; drop those that vanished inside them.
            current.services().forEach((id, settings) -> {
                if (services.containsKey(id)) {
                    return;
                }
                boolean insideScannedRoot = settings.path() != null && effectiveRoots.stream()
                        .anyMatch(r -> Path.of(settings.path()).startsWith(paths.expand(r).toAbsolutePath().normalize()));
                if (insideScannedRoot) {
                    removed.add(id);
                } else {
                    services.put(id, settings);
                }
            });
            List<String> mergedRoots = new ArrayList<>(current.workspaceRoots());
            effectiveRoots.stream().filter(r -> !mergedRoots.contains(r)).forEach(mergedRoots::add);
            return current.withServices(services).withWorkspaceRoots(mergedRoots);
        });
        return new ScanResult(found, added, removed, outsideTeam);
    }

    /**
     * The Quarkus projects in a folder, without remembering anything: what a scan of it would add.
     * Hidden projects are reported separately, so the folder browser can say why they are missing.
     */
    public Preview preview(String root) {
        KitoutConfig cfg = config.load();
        List<Project> visible = new ArrayList<>();
        List<String> hidden = new ArrayList<>();
        List<String> outsideTeam = new ArrayList<>();
        for (Project p : scanRoot(paths.expand(root))) {
            if (cfg.isHidden(p.path().toString())) {
                hidden.add(p.path().toString());
            } else if (!inScope(p)) {
                outsideTeam.add(p.path().toString());
            } else {
                visible.add(p);
            }
        }
        visible.sort(Comparator.comparing(Project::id));
        return new Preview(visible, hidden, outsideTeam);
    }

    public record Preview(List<Project> projects, List<String> hiddenPaths, List<String> outsideTeam) {
    }

    /**
     * All services: the configured projects, freshly read from disk (those whose folder is gone, or
     * that the team file does not name, are skipped), and the team file's image-only services.
     * A port the developer set (Options) replaces the detected one here, so every part of kitout sees the same port.
     */
    public List<Project> projects() {
        KitoutConfig cfg = config.load();
        List<Project> result = new ArrayList<>();
        java.util.Set<String> imageIds = new java.util.HashSet<>();
        team.current().ifPresent(t -> t.images().forEach(i -> {
            imageIds.add(i.id());
            result.add(withOverrides(imageOnly(i), cfg.serviceSettings(i.id())));
        }));
        cfg.services().forEach((id, settings) -> {
            if (settings.path() != null && !imageIds.contains(id)) {
                reader.read(Path.of(settings.path())).filter(this::inScope).map(p -> withOverrides(p, settings)).ifPresent(result::add);
            }
        });
        result.sort(Comparator.comparing(Project::id));
        return result;
    }

    public Optional<Project> find(String serviceId) {
        KitoutConfig cfg = config.load();
        Optional<Project> image = team.current().flatMap(t -> t.images().stream().filter(i -> i.id().equals(serviceId)).findFirst())
                .map(i -> withOverrides(imageOnly(i), cfg.serviceSettings(serviceId)));
        if (image.isPresent()) {
            return image;
        }
        ServiceSettings settings = cfg.services().get(serviceId);
        if (settings == null || settings.path() == null) {
            return Optional.empty();
        }
        return reader.read(Path.of(settings.path())).filter(this::inScope).map(p -> withOverrides(p, settings));
    }

    /** The developer's port, when set, instead of the detected one. */
    static Project withOverrides(Project p, ServiceSettings settings) {
        return settings.port() != null && settings.port() != p.devPort() ? p.withPort(settings.port()) : p;
    }

    /**
     * An image-only service as a project: no checkout (kitout's state folder stands in as its working
     * folder, nothing is written there), no build, the team's port and health path.
     */
    Project imageOnly(org.kitout.domain.team.TeamSetup.ImageService i) {
        String health = i.healthPath() == null ? "" : i.healthPath();
        return new Project(i.id(), i.name(), paths.stateDir(), Optional.empty(), org.kitout.domain.project.BuildTool.NONE,
                i.port(), false, Optional.empty(), List.of(), java.util.Map.of(),
                new org.kitout.domain.config.HealthEndpoint(i.port(), health, false), Optional.empty(),
                org.kitout.domain.project.ProjectType.IMAGE, List.of(), Optional.of(i.image()));
    }

    public Project require(String serviceId) {
        return find(serviceId).orElseThrow(() -> new NoSuchElementException(
                "Unknown service '" + serviceId + "'. Run a scan, or check that the folder still exists."));
    }

    /** The Quarkus projects in one folder (e.g. a cloned repository), without the team filter or remembering anything. */
    public List<Project> projectsIn(Path folder) {
        return scanRoot(folder);
    }

    /** Why a folder yields no service, when it yields none. */
    public Optional<String> whyNoService(Path folder) {
        return reader.whyNoService(folder);
    }

    List<Project> scanRoot(Path root) {
        List<Project> found = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return found;
        }
        try {
            Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && SKIP.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Optional<Project> project = reader.read(dir);
                    if (project.isPresent()) {
                        found.add(project.get());
                        return FileVisitResult.SKIP_SUBTREE;   // a repository's own subfolders are not separate services
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // unreadable root: nothing found there
        }
        return found;
    }
}
