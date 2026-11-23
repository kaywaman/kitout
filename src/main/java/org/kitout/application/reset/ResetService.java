package org.kitout.application.reset;

import org.kitout.application.KitoutPaths;
import org.kitout.application.build.BuildService;
import org.kitout.application.config.ConfigRepository;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.config.KitoutConfig.EnvVar;
import org.kitout.application.env.EnvironmentService;
import org.kitout.application.port.CommandRunner.Result;
import org.kitout.application.port.SecretStore;
import org.kitout.application.run.DockerCli;
import org.kitout.application.run.DockerCli.LocalImage;
import org.kitout.application.run.ServiceSupervisor;
import org.kitout.application.run.StateRepository;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.domain.identity.Redactor;
import org.kitout.domain.project.BuildTool;
import org.kitout.domain.project.Project;
import org.kitout.domain.run.ImageRef;
import org.kitout.application.team.TeamOverlay;
import org.kitout.application.team.TeamService;
import org.kitout.domain.team.TeamSetup;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Starts kitout over, in parts the developer chooses. Before anything is removed, the services kitout
 * runs are stopped and running builds are cancelled, so nothing keeps writing into what is deleted.
 *
 * <p>Never touched: the repositories (source code), compose files and the data of compose
 * environments, the docker login and git's credentials. Build output inside the projects is only
 * removed when asked for separately.
 */
@ApplicationScoped
public class ResetService {

    /**
     * @param settings    config.json, run state, kitout's secrets in the keyring (fallback token, secret variables)
     * @param logs        service logs, compose output, kitout's own log
     * @param builds      the build list and build logs
     * @param buildOutput {@code target/} (Maven) or {@code build/} (Gradle) inside each project
     * @param images      the service images kitout pulled, and leftover kitout containers
     */
    public record Options(boolean settings, boolean logs, boolean builds, boolean buildOutput, boolean images) {

        public boolean any() {
            return settings || logs || builds || buildOutput || images;
        }
    }

    /** What a reset would remove, so the dialog can say it before anything happens. */
    public record Preview(List<String> runningServices, int logFiles, long logBytes, int buildLogs, int builds,
                          List<String> buildOutputs, List<String> images, String configFile, int secrets) {
    }

    /** @param done one line per part that was cleaned; @param problems what could not be removed, and why */
    public record Outcome(List<String> done, List<String> problems) {
    }

    private final ConfigRepository config;
    private final StateRepository state;
    private final ServiceSupervisor supervisor;
    private final BuildService builds;
    private final DockerCli docker;
    private final SecretStore secrets;
    private final WorkspaceService workspace;
    private final KitoutPaths paths;
    private final TeamService team;

    public ResetService(ConfigRepository config, StateRepository state, ServiceSupervisor supervisor, BuildService builds,
                        DockerCli docker, SecretStore secrets, WorkspaceService workspace, KitoutPaths paths, TeamService team) {
        this.team = team;
        this.config = config;
        this.state = state;
        this.supervisor = supervisor;
        this.builds = builds;
        this.docker = docker;
        this.secrets = secrets;
        this.workspace = workspace;
        this.paths = paths;
    }

    public Preview preview() {
        List<Path> logs = logFiles();
        long bytes = logs.stream().mapToLong(ResetService::size).sum();
        return new Preview(runningServices(), logs.size(), bytes, files(paths.stateDir().resolve("builds")).size(), builds.jobs().size(),
                buildOutputs().stream().map(Path::toString).toList(),
                serviceImages().stream().map(LocalImage::reference).toList(),
                paths.configFile().toString(), secretKeys(config.load(), currentTeam()).size());
    }

    public Outcome reset(Options options) {
        if (!options.any()) {
            throw new IllegalArgumentException("Choose what to reset");
        }
        List<String> done = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        KitoutConfig cfg = config.load();   // read before settings go: images and secrets are found through it

        List<String> running = runningServices();
        for (String id : state.load().runs().keySet()) {
            try {
                supervisor.stop(id);
            } catch (RuntimeException e) {
                problems.add("Could not stop " + id + ": " + Redactor.redact(String.valueOf(e.getMessage())));
            }
        }
        if (!running.isEmpty()) {
            done.add("Stopped " + running.size() + " service(s)");
        }
        if (options.builds() || options.settings()) {
            int n = builds.clearHistory();
            if (options.builds() && n > 0) {
                done.add("Cancelled running builds and cleared the build list (" + n + ")");
            }
        }
        if (options.images()) {
            removeImages(done, problems);
        }
        if (options.logs()) {
            int n = deleteAll(logFiles().stream().filter(f -> !f.equals(paths.kitoutLog())).toList(), problems);
            truncate(paths.kitoutLog());
            done.add("Deleted " + n + " log file(s) and emptied kitout's log");
        }
        if (options.builds()) {
            done.add("Deleted " + deleteAll(files(paths.stateDir().resolve("builds")), problems) + " build log(s)");
        }
        if (options.buildOutput()) {
            List<Path> outputs = buildOutputs();
            outputs.forEach(dir -> deleteTree(dir, problems));
            done.add("Deleted the build output of " + outputs.size() + " project(s)");
        }
        if (options.settings()) {
            List<String> keys = secretKeys(cfg, currentTeam());
            keys.forEach(secrets::delete);
            // the team file location lives in config.json; its cached copy and the machine readings go too
            List<Path> files = new ArrayList<>(List.of(paths.configFile(), paths.stateFile(), paths.askpassScript(), paths.nativeBuildLock()));
            files.addAll(files(paths.stateDir().resolve("team")));
            files.add(paths.stateDir().resolve("session-token"));   // browsers need the link again after a restart
            int n = deleteAll(files, problems);
            if (team != null) {
                team.cleared();
            }
            done.add("Deleted the settings and run state (" + n + " file(s)) and " + keys.size() + " secret(s) from the keyring");
        }
        return new Outcome(done, problems);
    }

    private void removeImages(List<String> done, List<String> problems) {
        int containers = 0;
        for (String name : docker.kitoutContainers()) {
            if (docker.removeLeftover(name).succeeded()) {
                containers++;
            }
        }
        int removed = 0;
        for (LocalImage image : serviceImages()) {
            Result r = docker.removeImage(image.reference());
            if (r.succeeded()) {
                removed++;
            } else {
                problems.add("Kept " + image.reference() + ": " + Redactor.redact(r.firstLine()));
            }
        }
        done.add("Removed " + removed + " service image(s)" + (containers > 0 ? " and " + containers + " leftover container(s)" : ""));
    }

    private List<String> runningServices() {
        return List.copyOf(state.load().runs().keySet());
    }

    /** Local images of the services' repositories (the ones kitout pulls), tagged ones only. */
    List<LocalImage> serviceImages() {
        KitoutConfig cfg = config.load();
        Set<String> repositories = new HashSet<>();
        for (Project p : workspace.projects()) {
            try {
                repositories.add(ImageRef.parse(supervisor.imageRepository(p, cfg.serviceSettings(p.id()), cfg)).repository());
            } catch (IllegalStateException e) {
                // no registry or no git origin: kitout never pulled an image for it
            }
        }
        return docker.localImages().stream()
                .filter(i -> !i.tag().equals("<none>") && repositories.contains(i.repository()))
                .toList();
    }

    /** {@code target/} for Maven, {@code build/} for Gradle, only when it exists directly inside the project. */
    List<Path> buildOutputs() {
        List<Path> result = new ArrayList<>();
        KitoutConfig cfg = config.load();
        for (Project p : workspace.projects()) {
            if (cfg.serviceSettings(p.id()).runMode() == org.kitout.domain.run.RunMode.IDE) {
                continue;   // the IDE works with it: kitout does not touch it, not even on a reset
            }
            if (!p.buildTool().java()) {
                continue;   // Node and image-only services: kitout builds nothing there, so it removes nothing
            }
            Path out = p.path().resolve(p.buildTool() == BuildTool.GRADLE ? "build" : "target").normalize();
            if (out.startsWith(p.path()) && Files.isDirectory(out)) {
                result.add(out);
            }
        }
        return result;
    }

    private List<Path> logFiles() {
        List<Path> result = new ArrayList<>(files(paths.serviceLogDir()));
        result.addAll(files(paths.stateDir().resolve("compose")));
        Path kitoutLog = paths.kitoutLog();
        try (Stream<Path> s = Files.list(kitoutLog.getParent())) {
            s.filter(f -> f.getFileName().toString().startsWith(kitoutLog.getFileName().toString())).forEach(result::add);
        } catch (IOException e) {
            // no state folder yet
        }
        return result;
    }

    private Optional<TeamSetup> currentTeam() {
        try {
            return team == null ? Optional.empty() : team.current();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    static List<String> secretKeys(KitoutConfig cfg) {
        return secretKeys(cfg, Optional.empty());
    }

    /** kitout's secrets in the keyring: the fallback GitLab token, every secret variable, and your values for the team's secrets. */
    static List<String> secretKeys(KitoutConfig cfg, Optional<TeamSetup> teamSetup) {
        List<String> keys = new ArrayList<>();
        teamSetup.ifPresent(t -> {
            t.secrets().forEach(n -> keys.add(EnvironmentService.key(EnvironmentService.SHARED, n)));
            t.services().forEach(svc -> svc.secrets().forEach(n -> keys.add(EnvironmentService.key(TeamOverlay.serviceId(svc.repo()), n))));
        });
        if (!cfg.gitlab().host().isEmpty()) {
            keys.add(cfg.gitlab().host());
        }
        cfg.runtime().environment().stream().filter(EnvVar::secret)
                .forEach(v -> keys.add(EnvironmentService.key(EnvironmentService.SHARED, v.name())));
        for (Map.Entry<String, KitoutConfig.ServiceSettings> s : cfg.services().entrySet()) {
            s.getValue().environment().stream().filter(EnvVar::secret).forEach(v -> keys.add(EnvironmentService.key(s.getKey(), v.name())));
        }
        return keys.stream().distinct().toList();
    }

    private static List<Path> files(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static int deleteAll(List<Path> files, List<String> problems) {
        int n = 0;
        for (Path f : files) {
            try {
                if (Files.deleteIfExists(f)) {
                    n++;
                }
            } catch (IOException e) {
                problems.add("Could not delete " + f + ": " + e.getMessage());
            }
        }
        return n;
    }

    private static void deleteTree(Path dir, List<String> problems) {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            problems.add("Could not delete " + dir + ": " + e.getMessage());
        }
    }

    /** kitout's log is open while kitout runs: empty it instead of deleting it. */
    private static void truncate(Path file) {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "rw")) {
            f.setLength(0);
        } catch (IOException e) {
            // no log yet
        }
    }

    private static long size(Path f) {
        try {
            return Files.size(f);
        } catch (IOException e) {
            return 0;
        }
    }
}
