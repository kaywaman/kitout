package org.kitout.application.docker;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.ConfigRepository;
import org.kitout.application.config.KitoutConfig.TeamSettings;
import org.kitout.application.git.CredentialService;
import org.kitout.application.git.RepositoryFiles;
import org.kitout.application.port.GitLabApi;
import org.kitout.application.team.TeamService;
import org.kitout.domain.docker.ComposeFile;
import org.kitout.domain.identity.Redactor;
import org.kitout.domain.team.TeamSetup.FileRef;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The team's compose files for repositories that are not cloned: read from the repository like the
 * team file itself ({@link RepositoryFiles}: GitLab's API with a token, or git for any server), and
 * kept in {@code ~/.local/state/kitout/team/compose/}.
 *
 * <p>The copy keeps the file's folder from the repository ({@code compose/docker-compose-dev.yml}
 * stays in a folder named {@code compose}), so compose's project name, and with it the container and
 * volume names, is the same as from a clone. A clone found later is used instead, and the data stays.
 *
 * <p>Only the compose file itself is downloaded. A file that names files next to it ({@code ./init},
 * {@code env_file:}, {@code build:}) is not used from the copy: Docker would mount empty folders in
 * their place. The developer is asked to clone the repository instead.
 *
 * <p>The copy is read again when the team file was read again (every hour, or on "Read again"), or
 * when it is older than an hour. When the server cannot be reached, the last copy is used.
 */
@ApplicationScoped
public class TeamComposeDownloads {

    static final Duration REFRESH_AFTER = Duration.ofHours(1);
    /** After a failed download, wait this long before trying again: the Docker page asks every few seconds. */
    static final Duration RETRY_AFTER = Duration.ofMinutes(2);

    /**
     * @param file    the copy to give docker compose; null if there is none
     * @param problem why the copy cannot be used, for the Docker page; null if it can
     */
    public record Download(Path file, String problem) {

        public boolean usable() {
            return file != null && problem == null;
        }
    }

    private final RepositoryFiles files;
    private final ConfigRepository config;
    private final TeamService team;
    private final KitoutPaths paths;
    private final Map<String, Instant> failedAt = new ConcurrentHashMap<>();
    private final Map<String, String> lastProblem = new ConcurrentHashMap<>();

    public TeamComposeDownloads(GitLabApi gitlab, CredentialService credentials, ConfigRepository config, TeamService team, KitoutPaths paths) {
        this(new RepositoryFiles(gitlab, credentials, config, paths, (org.kitout.application.git.GitFileReader) null), config, team, paths);
    }

    @jakarta.inject.Inject
    public TeamComposeDownloads(RepositoryFiles files, ConfigRepository config, TeamService team, KitoutPaths paths) {
        this.files = files;
        this.config = config;
        this.team = team;
        this.paths = paths;
    }

    /** The downloaded copy of a team compose file, read from its repository if there is none or it is out of date. */
    public synchronized Download get(FileRef ref) {
        Path copy = copyOf(ref);
        Properties meta = readMeta(copy);
        String teamRead = String.valueOf(team.status().loadedAt());
        boolean fresh = Files.isRegularFile(copy)
                && teamRead.equals(meta.getProperty("teamRead"))
                && fetchedWithin(meta, REFRESH_AFTER);
        String key = ref.repo() + "/" + ref.path();
        if (!fresh && retryDue(key)) {
            try {
                download(ref, copy, teamRead);
                failedAt.remove(key);
                lastProblem.remove(key);
            } catch (RuntimeException | IOException e) {
                failedAt.put(key, Instant.now());
                lastProblem.put(key, describe(ref, e));
            }
        }
        if (!Files.isRegularFile(copy)) {
            return new Download(null, lastProblem.getOrDefault(key, ref.repo() + " is not cloned and " + ref.path() + " could not be read from the git server"));
        }
        String text;
        try {
            text = Files.readString(copy, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new Download(null, "The downloaded copy of " + ref.path() + " cannot be read (" + e.getMessage() + ")");
        }
        List<String> local = ComposeFile.localReferences(text);
        if (!local.isEmpty()) {
            return new Download(copy, ref.path() + " uses files next to it in " + ref.repo() + " (" + String.join(", ", local.subList(0, Math.min(3, local.size())))
                    + (local.size() > 3 ? ", ..." : "") + "), so it only works from a clone: clone " + ref.repo() + " under Team repositories");
        }
        return new Download(copy, null);
    }

    /** Forgets every copy, e.g. when the developer stops following the team file. */
    public synchronized void clear() {
        failedAt.clear();
        lastProblem.clear();
        Path dir = root();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // left behind; harmless
                }
            });
        } catch (IOException e) {
            // left behind; harmless
        }
    }

    /** Where the copy of {@code ref} goes: {@code team/compose/<group>/<project>/<path in the repository>}. */
    Path copyOf(FileRef ref) {
        Path repo = root().resolve(ref.repo()).normalize();
        Path file = repo.resolve(ref.path()).normalize();
        if (!repo.startsWith(root()) || !file.startsWith(repo)) {
            // the parser refuses "..", so this is only a guard
            throw new IllegalArgumentException(ref.repo() + "/" + ref.path() + " leaves the repository");
        }
        return file;
    }

    private Path root() {
        return paths.stateDir().resolve("team").resolve("compose").toAbsolutePath().normalize();
    }

    private void download(FileRef ref, Path copy, String teamRead) throws IOException {
        TeamSettings source = config.load().team();
        String host = source != null && source.host() != null ? source.host() : config.load().gitlab().host();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("no git host is set (Settings, Git)");
        }
        // the team file's own repository: the same branch as the team file; any other: its default branch
        String gitRef = source != null && Objects.equals(source.project(), ref.repo()) ? source.ref() : "HEAD";
        RepositoryFiles.File content = files.read(host, ref.repo(), ref.path(), gitRef);
        if (content.text().length() > ComposeService.MAX_COMPOSE_BYTES) {
            throw new IllegalStateException("it is larger than 1 MB");
        }
        if (!ComposeFile.looksLikeCompose(content.text())) {
            throw new IllegalStateException("it is not a compose file (no top-level 'services:')");
        }
        Files.createDirectories(copy.getParent());
        Path tmp = copy.resolveSibling(copy.getFileName() + ".part");
        Files.writeString(tmp, content.text(), StandardCharsets.UTF_8);
        Files.move(tmp, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        Properties meta = new Properties();
        meta.setProperty("teamRead", teamRead);
        meta.setProperty("fetchedAt", Instant.now().toString());
        meta.setProperty("commit", String.valueOf(content.version()));
        meta.setProperty("ref", gitRef);
        StringWriter w = new StringWriter();
        meta.store(w, "kitout: downloaded from " + host + "/" + ref.repo());
        Files.writeString(metaFile(copy), w.toString(), StandardCharsets.UTF_8);
    }

    private static String describe(FileRef ref, Exception e) {
        boolean notThere = e instanceof org.kitout.application.git.GitFileReader.NotFound
                || e instanceof GitLabApi.GitLabException g && g.status() == 404;
        String why = notThere
                ? ref.path() + " is not in " + ref.repo() + " (or your login cannot see it): check \"path\" in the team file"
                : ref.path() + " could not be read from " + ref.repo() + ": " + Redactor.redact(String.valueOf(e.getMessage()));
        return ref.repo() + " is not cloned, and " + why;
    }

    private boolean retryDue(String key) {
        Instant failed = failedAt.get(key);
        return failed == null || Duration.between(failed, Instant.now()).compareTo(RETRY_AFTER) >= 0;
    }

    private static boolean fetchedWithin(Properties meta, Duration max) {
        try {
            return Duration.between(Instant.parse(meta.getProperty("fetchedAt", "")), Instant.now()).compareTo(max) < 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Path metaFile(Path copy) {
        return copy.resolveSibling("." + copy.getFileName() + ".kitout");
    }

    private static Properties readMeta(Path copy) {
        Properties meta = new Properties();
        try {
            Path m = metaFile(copy);
            if (Files.exists(m)) {
                meta.load(new StringReader(Files.readString(m)));
            }
        } catch (IOException e) {
            // no meta: download again
        }
        return meta;
    }
}
