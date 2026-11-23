package org.kitout.application.team;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.PersonalConfigRepository;
import org.kitout.application.config.KitoutConfig.TeamSettings;
import org.kitout.application.git.CredentialService;
import org.kitout.application.git.RepositoryFiles;
import org.kitout.application.port.GitLabApi;
import org.kitout.application.port.JsonReader;
import org.kitout.domain.identity.Redactor;
import org.kitout.domain.team.TeamSetup;
import org.kitout.domain.team.TeamSetupParser;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * The team's {@code kitout-team.json}: where it comes from, the copy in use, and its problems.
 *
 * <p>The file is read from its repository without a clone (GitLab's API with a token, or git for any server) or from a local
 * path. The last good copy is kept in {@code ~/.local/state/kitout/team/}, so the CLI starts without
 * network, and a broken change to the file (a bad merge) never stops the team: kitout keeps using
 * the last good copy and shows what is wrong with the new one. A GitLab source is refreshed in the
 * background when the copy is older than an hour, or on request.
 */
@ApplicationScoped
public class TeamService implements TeamDefaults {

    static final Duration REFRESH_AFTER = Duration.ofHours(1);

    /** The CLI refreshes in the foreground ({@link #refreshIfDue()}); a background thread would die with the process. */
    static volatile boolean BACKGROUND_DISABLED = false;

    public static void foregroundOnly() {
        BACKGROUND_DISABLED = true;
    }

    /**
     * @param setup    the team setup in use (the last good copy); empty if none was ever valid
     * @param problems why the newest copy cannot be used (placeholders, credentials, mistakes); empty if fine
     * @param warnings worth fixing in the file, but it works
     * @param version  the GitLab commit of the copy in use, or the file's modification time
     */
    public record Status(boolean configured, String location, Optional<TeamSetup> setup, List<String> problems,
                         List<String> warnings, String version, Instant loadedAt) {

        static Status none() {
            return new Status(false, "", Optional.empty(), List.of(), List.of(), "", null);
        }
    }

    private final PersonalConfigRepository config;
    private final GitLabApi gitlab;
    private final CredentialService credentials;
    private final RepositoryFiles files;
    private final JsonReader json;
    private final KitoutPaths paths;
    private final String kitoutVersion;
    private volatile Status status;
    private volatile Instant lastRefreshAttempt = Instant.EPOCH;

    /** Where the team file is when the developer has not said; followed on the first call. */
    private final Optional<TeamSettings> preset;
    /** Why the preset in kitout's build cannot be used (a typo in application.properties); empty if fine. */
    private final Optional<String> presetProblem;

    public TeamService(PersonalConfigRepository config, GitLabApi gitlab, CredentialService credentials, JsonReader json, KitoutPaths paths,
                       String kitoutVersion, Optional<String> defaultLocation) {
        this(config, gitlab, credentials, json, paths, kitoutVersion, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                defaultLocation);
    }

    /**
     * The preset comes from kitout's build ({@code application.properties}) or the environment, named like a team
     * compose file: {@code kitout.team.repo} and {@code kitout.team.path}, with {@code kitout.team.host} and
     * {@code kitout.team.ref} (environment: {@code KITOUT_TEAM_REPO}, ...). The older single
     * {@code kitout.team.location} (a link) is still read when no repository is set.
     *
     * @param host the git host; empty: the one in the developer's settings (Settings, Git)
     */
    public TeamService(PersonalConfigRepository config, GitLabApi gitlab, CredentialService credentials, JsonReader json, KitoutPaths paths,
                       String kitoutVersion, Optional<String> host, Optional<String> repo, Optional<String> path, Optional<String> ref,
                       Optional<String> location) {
        this(config, gitlab, credentials, json, paths, kitoutVersion, host, repo, path, ref, location,
                new RepositoryFiles(gitlab, credentials, config, paths, (org.kitout.application.git.GitFileReader) null));
    }

    /** @param files reads the team file from its repository (GitLab's API, or git for any server) */
    @jakarta.inject.Inject
    public TeamService(PersonalConfigRepository config, GitLabApi gitlab, CredentialService credentials, JsonReader json, KitoutPaths paths,
                       @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev") String kitoutVersion,
                       @ConfigProperty(name = "kitout.team.host") Optional<String> host,
                       @ConfigProperty(name = "kitout.team.repo") Optional<String> repo,
                       @ConfigProperty(name = "kitout.team.path") Optional<String> path,
                       @ConfigProperty(name = "kitout.team.ref") Optional<String> ref,
                       @ConfigProperty(name = "kitout.team.location") Optional<String> location,
                       RepositoryFiles files) {
        this.files = files;
        Optional<TeamSettings> chosen = Optional.empty();
        String problem = null;
        try {
            if (repo.filter(r -> !r.isBlank()).isPresent()) {
                String h = host.filter(x -> !x.isBlank()).orElseGet(() -> config.load().gitlab().host());
                chosen = Optional.of(TeamSettings.of(h, repo.get(), path.orElse(null), ref.orElse(null)));
            } else if (location.filter(l -> !l.isBlank()).isPresent()) {
                chosen = Optional.of(TeamSettings.parse(location.get().strip()));
            }
        } catch (IllegalArgumentException e) {
            problem = "The team file set in kitout's build (kitout.team.*) cannot be used: " + e.getMessage();
        }
        this.preset = chosen;
        this.presetProblem = Optional.ofNullable(problem);
        this.config = config;
        this.gitlab = gitlab;
        this.credentials = credentials;
        this.json = json;
        this.paths = paths;
        this.kitoutVersion = kitoutVersion;
    }

    @Override
    public Optional<TeamSetup> current() {
        return status().setup();
    }

    public Status status() {
        Status s = status;
        if (s == null) {
            synchronized (this) {
                if (status == null) {
                    status = loadCached();
                }
                s = status;
            }
        }
        refreshInBackgroundIfOld(s);
        return s;
    }

    /** Reads the file again now (GitLab or local) and returns the new status. */
    public synchronized Status refresh() {
        lastRefreshAttempt = Instant.now();
        rememberAttempt(lastRefreshAttempt);
        TeamSettings source = config.load().team();
        if (!source.configured()) {
            status = Status.none();
            return status;
        }
        Optional<TeamSetup> previous = status == null ? Optional.empty() : status.setup();
        String text;
        String version;
        try {
            if (source.file() != null) {
                Path file = paths.expand(source.file());
                text = Files.readString(file);
                version = Files.getLastModifiedTime(file).toInstant().toString();
            } else {
                RepositoryFiles.File content = readFromRepository(source);
                text = content.text();
                version = content.version();
            }
        } catch (IOException | RuntimeException e) {
            status = new Status(true, source.location(), previous,
                    List.of("Could not read the team file from " + source.location() + ": " + Redactor.redact(String.valueOf(e.getMessage()))
                            + (previous.isPresent() ? ". Using the last good copy." : "")),
                    List.of(), status == null ? "" : status.version(), status == null ? null : status.loadedAt());
            return status;
        }
        Status parsed = parse(source, text, version, Instant.now());
        if (parsed.setup().isPresent()) {
            writeCache(source, text, version, parsed.loadedAt());
            status = parsed;
        } else {
            // a broken file never replaces a good one: keep using the last good copy, show what is wrong
            status = new Status(true, source.location(), previous, parsed.problems(), parsed.warnings(),
                    status == null ? "" : status.version(), status == null ? null : status.loadedAt());
        }
        return status;
    }

    /** Reads the team file from its repository. */
    private RepositoryFiles.File readFromRepository(TeamSettings source) {
        return files.read(source.host(), source.project(), source.path(), source.ref());
    }

    /**
     * For short-lived CLI calls: reads the team file again now if the copy in use is older than an
     * hour (a background refresh would end with the process). A failed attempt is remembered, so
     * working offline costs one try an hour, not one per command.
     */
    public Status refreshIfDue() {
        Status s = status();
        if (s.configured() && Duration.between(lastRefreshAttempt, Instant.now()).compareTo(REFRESH_AFTER) >= 0) {
            return refresh();
        }
        return s;
    }

    /** Points kitout at a team file given as one text (a link, {@code host/group/project}, or a local path) and reads it. */
    public Status use(String location) {
        return use(TeamSettings.parse(location));
    }

    /** Points kitout at a team file and reads it. */
    public Status use(TeamSettings settings) {
        config.update(c -> c.withTeam(settings));
        try {
            Files.deleteIfExists(declinedFile());
        } catch (IOException e) {
            // harmless
        }
        synchronized (this) {
            status = null;
        }
        deleteCache();
        return refresh();
    }

    /** Stops using a team file; the developer's own settings stay. */
    public Status forget() {
        config.update(c -> c.withTeam(TeamSettings.none()));
        deleteCache();
        try {
            Files.createDirectories(declinedFile().getParent());
            Files.writeString(declinedFile(), "The developer stopped following the team file.\n");
        } catch (IOException e) {
            // the preset location may be followed again on the next start
        }
        synchronized (this) {
            status = Status.none();
        }
        return status;
    }

    /** After a reset removed config.json and the cache: forget what is held in memory too. */
    public synchronized void cleared() {
        status = null;   // read again on the next call: a preset location is then followed again
        lastRefreshAttempt = Instant.EPOCH;
    }

    Status parse(TeamSettings source, String text, String version, Instant at) {
        try {
            TeamSetupParser.Result r = TeamSetupParser.parse(json.readObject(text), kitoutVersion);
            return new Status(true, source.location(), r.setup(), r.problems(), r.warnings(), version, at);
        } catch (IllegalArgumentException e) {
            return new Status(true, source.location(), Optional.empty(), List.of(e.getMessage()), List.of(), version, at);
        }
    }

    /** The location kitout's build or environment suggests, for people; empty if none. */
    public Optional<String> defaultLocation() {
        return preset.map(TeamSettings::location);
    }

    /** The team file kitout's build or environment suggests; empty if none. */
    public Optional<TeamSettings> preset() {
        return preset;
    }

    /** Why the suggested team file cannot be used; empty if it can (or there is none). */
    public Optional<String> presetProblem() {
        return presetProblem;
    }

    private Status loadCached() {
        TeamSettings source = config.load().team();
        if (!source.configured() && preset.isPresent() && !Files.exists(declinedFile())) {
            // first call with a preset location: follow it right away, so the first screen already shows every check
            try {
                config.update(c -> c.withTeam(preset.get()));
                status = Status.none();
                return refresh();
            } catch (RuntimeException e) {
                return Status.none();
            }
        }
        if (!source.configured()) {
            return Status.none();
        }
        Path file = cacheFile();
        Properties meta = readMeta();
        if (Files.exists(file) && source.location().equals(meta.getProperty("location"))) {
            try {
                Instant at = Instant.parse(meta.getProperty("loadedAt", Instant.EPOCH.toString()));
                Instant attempted = Instant.parse(meta.getProperty("attemptedAt", at.toString()));
                lastRefreshAttempt = attempted.isAfter(at) ? attempted : at;
                return parse(source, Files.readString(file), meta.getProperty("version", ""), at);
            } catch (IOException | RuntimeException e) {
                // unreadable cache: read the source instead
            }
        }
        status = Status.none();
        return refresh();
    }

    private void refreshInBackgroundIfOld(Status s) {
        if (BACKGROUND_DISABLED || !s.configured() || Duration.between(lastRefreshAttempt, Instant.now()).compareTo(REFRESH_AFTER) < 0) {
            return;
        }
        lastRefreshAttempt = Instant.now();
        Thread.ofVirtual().name("team-refresh").start(this::refresh);
    }

    private void writeCache(TeamSettings source, String text, String version, Instant at) {
        try {
            Files.createDirectories(cacheFile().getParent());
            Files.writeString(cacheFile(), text, StandardCharsets.UTF_8);
            Properties meta = new Properties();
            meta.setProperty("location", source.location());
            meta.setProperty("version", version);
            meta.setProperty("loadedAt", at.toString());
            StringWriter w = new StringWriter();
            meta.store(w, "the team file kitout uses; written by kitout");
            Files.writeString(metaFile(), w.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // no cache: the next start reads the source again
        }
    }

    private void rememberAttempt(Instant at) {
        Properties meta = readMeta();
        if (meta.isEmpty()) {
            return;
        }
        meta.setProperty("attemptedAt", at.toString());
        try {
            StringWriter w = new StringWriter();
            meta.store(w, "the team file kitout uses; written by kitout");
            Files.writeString(metaFile(), w.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // the next call tries again
        }
    }

    private Properties readMeta() {
        Properties meta = new Properties();
        try {
            if (Files.exists(metaFile())) {
                meta.load(new StringReader(Files.readString(metaFile())));
            }
        } catch (IOException e) {
            // empty meta
        }
        return meta;
    }

    private void deleteCache() {
        try {
            Files.deleteIfExists(cacheFile());
            Files.deleteIfExists(metaFile());
        } catch (IOException e) {
            // nothing cached
        }
    }

    private Path cacheFile() {
        return paths.stateDir().resolve("team").resolve("kitout-team.json");
    }

    /** Written by "Stop following": the preset location is then not followed again by itself. */
    private Path declinedFile() {
        return paths.stateDir().resolve("team").resolve("declined");
    }

    private Path metaFile() {
        return paths.stateDir().resolve("team").resolve("team.properties");
    }
}
