package org.kitout.application.workspace;

import org.kitout.application.KitoutPaths;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Lists folders for the folder picker in the UI. A browser cannot show the developer's file
 * system, so the daemon does it, with limits:
 * <ul>
 *   <li>only below the home folder (after resolving symbolic links), never system folders</li>
 *   <li>folders only; the one exception is {@link #files}, which the compose file picker uses to
 *       list YAML files by name (contents are read only by the caller that asks for one file)</li>
 *   <li>hidden folders ({@code .cache}, {@code .ssh}, ...) are left out</li>
 *   <li>at most {@link #MAX_ENTRIES} entries per folder</li>
 * </ul>
 */
@ApplicationScoped
public class FolderBrowser {

    static final int MAX_ENTRIES = 500;

    /** One sub-folder. {@code quarkusProject}: its build file mentions Quarkus. {@code gitRepository}: it has a .git folder. */
    public record Entry(String name, String path, boolean quarkusProject, boolean gitRepository) {
    }

    /** {@code parent} is null at the home folder; {@code truncated} if more than MAX_ENTRIES folders exist. */
    public record Listing(String path, String parent, String home, List<Entry> entries, boolean truncated) {
    }

    private final Path home;
    private final ProjectReader reader;

    @Inject
    public FolderBrowser(KitoutPaths paths, ProjectReader reader) {
        this(paths.home(), reader);
    }

    FolderBrowser(Path home, ProjectReader reader) {
        this.home = realPath(home);
        this.reader = reader;
    }

    /** @param path a folder below home; {@code ~}, {@code ~/...} and empty mean home */
    public Listing list(String path) {
        Path dir = resolve(path);
        List<Entry> entries = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                if (entries.size() >= MAX_ENTRIES) {
                    truncated = true;
                    break;
                }
                entries.add(new Entry(name, child.toString(), reader.looksLikeService(child), Files.isDirectory(child.resolve(".git"))));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + dir + ": " + e.getMessage());
        }
        entries.sort(Comparator.comparing((Entry e) -> e.name().toLowerCase()));
        String parent = dir.equals(home) ? null : dir.getParent().toString();
        return new Listing(dir.toString(), parent, home.toString(), entries, truncated);
    }

    /** A file in a folder, for a file picker. */
    public record FileEntry(String name, String path) {
    }

    /**
     * The non-hidden files in {@code folder} whose name {@code accept}s, sorted, at most
     * {@link #MAX_ENTRIES}. The folder is checked like {@link #list}.
     */
    public List<FileEntry> files(String folder, java.util.function.Predicate<String> accept) {
        Path dir = resolve(folder);
        List<FileEntry> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, Files::isRegularFile)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                if (name.startsWith(".") || !accept.test(name)) {
                    continue;
                }
                if (result.size() >= MAX_ENTRIES) {
                    break;
                }
                result.add(new FileEntry(name, child.toString()));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + dir + ": " + e.getMessage());
        }
        result.sort(Comparator.comparing((FileEntry e) -> e.name().toLowerCase()));
        return result;
    }

    /** Resolves and checks a file path: it must exist, be a regular file, and lie inside home. */
    public Path resolveFile(String path) {
        String p = path == null ? "" : path.strip();
        Path candidate = p.startsWith("~/") ? home.resolve(p.substring(2)) : Path.of(p);
        if (p.isEmpty() || !candidate.isAbsolute() || !Files.isRegularFile(candidate)) {
            throw new NoSuchElementException("No such file: " + p);
        }
        Path real = realPath(candidate);
        if (!real.startsWith(home)) {
            throw new IllegalArgumentException("kitout only uses files inside your home folder (" + home + ")");
        }
        return real;
    }

    /** Resolves and checks a path: it must exist, be a folder, and lie inside home. */
    public Path resolve(String path) {
        String p = path == null ? "" : path.strip();
        Path candidate;
        if (p.isEmpty() || p.equals("~")) {
            candidate = home;
        } else if (p.startsWith("~/")) {
            candidate = home.resolve(p.substring(2));
        } else {
            candidate = Path.of(p);
        }
        if (!candidate.isAbsolute()) {
            candidate = home.resolve(candidate);
        }
        if (!Files.isDirectory(candidate)) {
            throw new NoSuchElementException("No such folder: " + candidate);
        }
        Path real = realPath(candidate);
        if (!real.startsWith(home)) {
            throw new IllegalArgumentException("kitout only shows folders inside your home folder (" + home + ")");
        }
        return real;
    }

    private static Path realPath(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }
}
