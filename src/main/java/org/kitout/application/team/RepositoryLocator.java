package org.kitout.application.team;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.ConfigRepository;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.git.GitCli;
import org.kitout.domain.clone.CloneTarget;
import org.kitout.domain.identity.RemoteUrlParser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a team repository is on this machine: where kitout clones it ({@code <clone folder>/<group>/<project>}),
 * or, if it was cloned by hand somewhere else, a checkout in the clone folder or the workspace
 * folders whose git origin is that repository. Found places are remembered while they exist.
 */
@ApplicationScoped
public class RepositoryLocator {

    static final int MAX_DEPTH = 5;
    private static final Set<String> SKIP = Set.of("node_modules", "target", "build", ".git", ".idea", ".vscode", "src", "dist", ".cache");

    private final ConfigRepository config;
    private final KitoutPaths paths;
    private final GitCli git;
    private final Map<String, Path> found = new ConcurrentHashMap<>();

    @Inject
    public RepositoryLocator(ConfigRepository config, KitoutPaths paths, GitCli git) {
        this.config = config;
        this.paths = paths;
        this.git = git;
    }

    /** Where kitout clones the repository: the place it is looked for first. */
    public Path cloneTarget(String repo) {
        KitoutConfig cfg = config.load();
        return CloneTarget.resolve(paths.expand(cfg.gitlab().workspaceRoot()).toAbsolutePath().normalize(), repo);
    }

    public Optional<Path> locate(String repo) {
        String key = repo.toLowerCase(java.util.Locale.ROOT);
        Path known = found.get(key);
        if (known != null && Files.isDirectory(known)) {
            return Optional.of(known);
        }
        found.remove(key);
        Path target;
        try {
            target = cloneTarget(repo);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (Files.isDirectory(target)) {
            found.put(key, target);
            return Optional.of(target);
        }
        Optional<Path> elsewhere = search(repo);
        elsewhere.ifPresent(p -> found.put(key, p));
        return elsewhere;
    }

    /** A checkout named like the project whose origin is the repository, below the clone and workspace folders. */
    private Optional<Path> search(String repo) {
        String name = repo.substring(repo.lastIndexOf('/') + 1);
        KitoutConfig cfg = config.load();
        List<String> roots = new ArrayList<>(cfg.workspaceRoots());
        roots.add(cfg.gitlab().workspaceRoot());
        for (String r : roots) {
            Path root = paths.expand(r).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                continue;
            }
            List<Path> candidates = new ArrayList<>();
            try {
                Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (!dir.equals(root) && SKIP.contains(dir.getFileName().toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (Files.isDirectory(dir.resolve(".git"))) {
                            if (dir.getFileName().toString().equalsIgnoreCase(name)) {
                                candidates.add(dir);
                            }
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                continue;
            }
            for (Path c : candidates) {
                boolean same = git.originUrl(c).flatMap(RemoteUrlParser::parse)
                        .map(id -> id.fullPath().equalsIgnoreCase(repo)).orElse(false);
                if (same) {
                    return Optional.of(c);
                }
            }
        }
        return Optional.empty();
    }
}
