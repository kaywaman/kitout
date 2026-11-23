package org.kitout.application.run;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.ConfigRepository;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.config.KitoutConfig.ServiceSettings;
import org.kitout.application.env.EnvironmentService;
import org.kitout.application.port.CommandRunner.Result;
import org.kitout.application.port.NetworkProbe;
import org.kitout.application.port.NetworkProbe.Health;
import org.kitout.application.port.NetworkProbe.HealthCheck;
import org.kitout.application.port.ProcessControl;
import org.kitout.application.port.ProcessControl.ProcessRef;
import org.kitout.application.registry.RegistryLogins;
import org.kitout.application.run.RunState.Phase;
import org.kitout.application.run.RunState.Run;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.domain.identity.Redactor;
import org.kitout.domain.project.Project;
import org.kitout.domain.run.DockerOptions;
import org.kitout.domain.run.ImagePath;
import org.kitout.domain.run.ImageRef;
import org.kitout.domain.run.LaunchPlanner;
import org.kitout.domain.run.LaunchSpec;
import org.kitout.domain.run.RunMode;
import org.kitout.domain.run.ServiceHealth;
import org.kitout.domain.run.ServiceState;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts, stops and watches services. Owns the service lifecycle (see {@link ServiceState}).
 *
 * <p>State is derived, not stored: the state file says which process group belongs to a
 * service, and the status is computed from "is the group alive" and "does the health endpoint
 * answer". This is why the daemon and the CLI always agree, and why running services are
 * re-adopted after kitout restarts.
 *
 * <p>Started means "the readiness check answered for the first time" (or the port answers, for
 * services without one). That moment is stored, so the start time can be shown. From then on the
 * service is RUNNING as long as its process lives; a failing readiness check makes it UNHEALTHY,
 * never FAILED, and kitout does not stop it: the developer decides.
 */
@ApplicationScoped
public class ServiceSupervisor {

    private static final Logger LOG = Logger.getLogger(ServiceSupervisor.class);
    static final Duration STOP_GRACE = Duration.ofSeconds(10);
    static final Duration MAX_PULL = Duration.ofMinutes(15);
    static final Duration READY_POLL = Duration.ofMillis(300);

    private final WorkspaceService workspace;
    private final ConfigRepository config;

    /** Starts the team's compose environments before a service; null in unit tests. */
    @jakarta.inject.Inject
    org.kitout.application.docker.ComposeService compose;
    private final StateRepository state;
    private final ProcessControl processes;
    private final NetworkProbe network;
    private final DockerCli docker;
    private final ArtifactLocator artifacts;
    private final LogService logs;
    private final KitoutPaths paths;
    private final RegistryLogins registryLogins;
    private final EnvironmentService environment;
    private final Set<String> stopping = ConcurrentHashMap.newKeySet();

    public ServiceSupervisor(WorkspaceService workspace, ConfigRepository config, StateRepository state,
                             ProcessControl processes, NetworkProbe network, DockerCli docker,
                             ArtifactLocator artifacts, LogService logs, KitoutPaths paths, RegistryLogins registryLogins,
                             EnvironmentService environment) {
        this.registryLogins = registryLogins;
        this.environment = environment;
        this.workspace = workspace;
        this.config = config;
        this.state = state;
        this.processes = processes;
        this.network = network;
        this.docker = docker;
        this.artifacts = artifacts;
        this.logs = logs;
        this.paths = paths;
    }

    // ---- status ---------------------------------------------------------------------------

    public List<ServiceStatus> statuses() {
        KitoutConfig cfg = config.load();
        RunState runs = state.load();
        return workspace.projects().stream().map(p -> status(p, cfg, runs)).toList();
    }

    public ServiceStatus status(String serviceId) {
        return status(workspace.require(serviceId), config.load(), state.load());
    }

    ServiceStatus status(Project project, KitoutConfig cfg, RunState runs) {
        ServiceSettings settings = fit(project, cfg.serviceSettings(project.id()));
        int port = project.devPort();
        Optional<Run> run = runs.run(project.id());
        // A process kitout started is reported (and can be stopped) even if the service was switched
        // to IDE mode meanwhile; the IDE view applies once it is stopped.
        if (settings.runMode() == RunMode.IDE && run.isEmpty()) {
            return ideStatus(project, port, settings.checksHealth(project.hasHealthEndpoint()));
        }
        if (run.isEmpty()) {
            String detail = network.canConnect("localhost", port, Duration.ofMillis(300))
                    ? "Port " + port + " is in use by " + network.portHolder(port).orElse("another process")
                    : "";
            return new ServiceStatus(project.id(), project.name(), ServiceState.STOPPED, settings.runMode(), port, detail,
                    Optional.empty(), ServiceHealth.UNKNOWN, Optional.empty());
        }
        Run r = run.get();
        if (stopping.contains(project.id())) {
            return notRunning(project, ServiceState.STOPPING, r, "Stopping");
        }
        if (r.phase() == Phase.PULLING) {
            boolean stuck = Instant.now().isAfter(r.startedAt().plus(MAX_PULL));
            return stuck
                    ? notRunning(project, ServiceState.FAILED, r, "Pulling the image did not finish; see the log")
                    : notRunning(project, ServiceState.STARTING, r, r.mode() == RunMode.SCRIPT ? "Installing the npm packages (npm install)" : "Pulling the image");
        }
        if (!processes.isAlive(new ProcessRef(r.pgid(), r.processStart()))) {
            return notRunning(project, ServiceState.FAILED, r, "The process exited unexpectedly; see the log");
        }
        logs.rotateIfLarge(paths.serviceLog(project.id()));
        boolean checksHealth = settings.checksHealth(project.hasHealthEndpoint());
        HealthCheck check = probe(project, checksHealth);
        if (r.readyAt() == null) {
            if (answersReady(check)) {
                return running(project, recordReady(project.id(), r), check, checksHealth);
            }
            if (Instant.now().isAfter(r.startedAt().plusSeconds(r.startTimeoutSeconds()))) {
                return notRunning(project, ServiceState.FAILED, r, "Not ready after " + r.startTimeoutSeconds() + " s ("
                        + (checksHealth ? project.health().path() + " on port " + project.health().port() : "port " + project.devPort() + " does not answer")
                        + "); see the log, then stop or restart it");
            }
            return notRunning(project, ServiceState.STARTING, r, checksHealth ? "Waiting for the readiness check" : "Waiting for port " + project.devPort());
        }
        // Once ready, the service stays RUNNING while its process lives; readiness is reported as health.
        return running(project, r, check, checksHealth);
    }

    /**
     * Asks the readiness check, or, with the health check switched off for this service (e.g. a BFF
     * whose health endpoint is blocked), only whether its port answers. That counts as "no health check".
     */
    HealthCheck probe(Project project, boolean checksHealth) {
        if (project.devPort() <= 0) {
            return HealthCheck.of(Health.NO_HEALTH_ENDPOINT);   // no port to watch: started while its process lives
        }
        if (checksHealth && project.hasHealthEndpoint()) {
            return network.health(project.health().port(), project.health().path());
        }
        return network.canConnect("localhost", project.devPort(), Duration.ofMillis(300))
                ? HealthCheck.of(Health.NO_HEALTH_ENDPOINT)
                : HealthCheck.of(Health.UNREACHABLE);
    }

    /** A first answer from the service counts as started: ready, or no readiness check at all. */
    private static boolean answersReady(HealthCheck check) {
        return check.health() == Health.READY || check.health() == Health.NO_HEALTH_ENDPOINT;
    }

    private static ServiceStatus running(Project p, Run r, HealthCheck check, boolean checksHealth) {
        return new ServiceStatus(p.id(), p.name(), ServiceState.RUNNING, r.mode(), r.port(), healthDetail(p, check, checksHealth),
                Optional.of(r.startedAt()), health(check), r.startupTime());
    }

    private static ServiceStatus notRunning(Project p, ServiceState s, Run r, String detail) {
        return new ServiceStatus(p.id(), p.name(), s, r.mode(), r.port(), detail, Optional.of(r.startedAt()),
                ServiceHealth.UNKNOWN, r.startupTime());
    }

    static ServiceHealth health(HealthCheck check) {
        return switch (check.health()) {
            case READY -> ServiceHealth.HEALTHY;
            case NO_HEALTH_ENDPOINT -> ServiceHealth.NO_HEALTH_CHECK;
            case NOT_READY, UNREACHABLE -> ServiceHealth.UNHEALTHY;
        };
    }

    static String healthDetail(Project p, HealthCheck check) {
        return healthDetail(p, check, true);
    }

    static String healthDetail(Project p, HealthCheck check, boolean checksHealth) {
        if (p.devPort() <= 0) {
            return "Running; it has no port to watch (set one under Options for a port check)";
        }
        if (!checksHealth || !p.hasHealthEndpoint()) {
            String why = checksHealth ? "No health endpoint" : "Health check switched off";
            return check.health() == Health.UNREACHABLE
                    ? "No answer on port " + p.devPort()
                    : why + "; port " + p.devPort() + " answers";
        }
        return switch (check.health()) {
            case READY -> "Healthy";
            // the UI and CLI show the health next to the detail, so the detail only adds the why
            case NO_HEALTH_ENDPOINT -> switch (p.type()) {
                case SPRING_BOOT -> "Port answers; add spring-boot-starter-actuator for a readiness check";
                case QUARKUS -> "Port answers; add quarkus-smallrye-health for a readiness check";
                default -> "Port answers; no readiness check at " + p.health().path();
            };
            case NOT_READY -> check.downChecks().isEmpty()
                    ? "Readiness check reports DOWN"
                    : String.join(", ", check.downChecks()) + " DOWN";
            case UNREACHABLE -> "No answer on port " + p.health().port();
        };
    }

    /**
     * Records the moment the service first answered, once, and writes it to the log. Returns the
     * run as stored. Guarded by the process group, so a restart is never mixed up with the last run.
     */
    private Run recordReady(String serviceId, Run seen) {
        Instant at = Instant.now();
        AtomicBoolean first = new AtomicBoolean();
        RunState updated = state.update(s -> s.run(serviceId)
                .filter(x -> x.readyAt() == null && x.pgid() == seen.pgid())
                .map(x -> {
                    first.set(true);
                    return s.with(serviceId, x.ready(at));
                })
                .orElse(s));
        Run stored = updated.run(serviceId).orElse(seen.ready(at));
        if (first.get()) {
            stored.startupTime().ifPresent(d -> logs.mark(paths.serviceLog(serviceId), "ready after " + formatSeconds(d)));
        }
        return stored;
    }

    public static String formatSeconds(Duration d) {
        return String.format(java.util.Locale.ROOT, "%.1f s", d.toMillis() / 1000.0);
    }

    /**
     * Polls the readiness check every {@link #READY_POLL} until the service answers, so the start
     * time is measured precisely even when no UI or CLI is asking for the status.
     */
    private void watchUntilReady(String serviceId) {
        Thread.ofVirtual().name("ready-" + serviceId).start(() -> {
            while (true) {
                try {
                    Thread.sleep(READY_POLL);
                } catch (InterruptedException e) {
                    return;
                }
                Optional<Run> current = state.load().run(serviceId);
                if (current.isEmpty() || current.get().readyAt() != null) {
                    return;
                }
                Run r = current.get();
                if (r.phase() == Phase.PULLING) {
                    continue;
                }
                if (Instant.now().isAfter(r.startedAt().plusSeconds(r.startTimeoutSeconds()))
                        || !processes.isAlive(new ProcessRef(r.pgid(), r.processStart()))) {
                    return;
                }
                Optional<Project> project = workspace.find(serviceId);
                if (project.isEmpty()) {
                    return;
                }
                boolean checksHealth = config.load().serviceSettings(serviceId).checksHealth(project.get().hasHealthEndpoint());
                if (answersReady(probe(project.get(), checksHealth))) {
                    recordReady(serviceId, r);
                    return;
                }
            }
        });
    }

    private ServiceStatus ideStatus(Project project, int port, boolean checksHealth) {
        HealthCheck check = probe(project, checksHealth);
        if (check.health() == Health.UNREACHABLE && !network.canConnect("localhost", port, Duration.ofMillis(300))) {
            return new ServiceStatus(project.id(), project.name(), ServiceState.STOPPED, RunMode.IDE, port,
                    "Not running. Start it in your IDE with Quarkus dev mode", Optional.empty(), ServiceHealth.UNKNOWN, Optional.empty());
        }
        String detail = "In your IDE. " + healthDetail(project, check, checksHealth);
        return new ServiceStatus(project.id(), project.name(), ServiceState.RUNNING, RunMode.IDE, port, detail,
                Optional.empty(), health(check), Optional.empty());
    }

    // ---- start ----------------------------------------------------------------------------

    /**
     * Starts one service and returns at once; the status moves from STARTING to RUNNING or FAILED.
     * Starting a service that is already starting or running does nothing.
     *
     * @throws IllegalStateException when the service cannot start (port taken, nothing built, IDE mode)
     */
    /**
     * The run mode of a service that runs (or starts) right now; empty if it does not run. A clean
     * build of it, or removing its build output, would pull the files from under it in IDE, JVM and
     * native mode.
     */
    public Optional<RunMode> runningMode(String serviceId) {
        try {
            ServiceStatus s = status(serviceId);
            return s.state() == ServiceState.RUNNING || s.state() == ServiceState.STARTING ? Optional.of(s.mode()) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Why the service cannot be built now; empty if it can. A service in IDE mode belongs to the IDE:
     * kitout neither builds it nor touches its build output. Otherwise a clean build would delete what a
     * running service runs from.
     */
    public Optional<String> cleanBuildConflict(String serviceId) {
        if (config.load().serviceSettings(serviceId).runMode() == RunMode.IDE) {
            return Optional.of(serviceId + " is in IDE mode: kitout leaves it and its build to your IDE. Change its run mode to build it with kitout");
        }
        return runningMode(serviceId).filter(m -> m != RunMode.IMAGE).map(m -> "Stop " + serviceId + " first: it runs "
                + (m == RunMode.IDE ? "in your IDE from" : "from") + " its build output, and a build starts with clean, which deletes it");
    }

    public ServiceStatus start(String serviceId) {
        Project project = workspace.require(serviceId);
        KitoutConfig cfg = config.load();
        ServiceSettings settings = fit(project, cfg.serviceSettings(serviceId));
        ServiceStatus current = status(project, cfg, state.load());
        if (current.state() == ServiceState.STARTING || current.state() == ServiceState.RUNNING) {
            return current;
        }
        if (settings.runMode() == RunMode.IDE) {
            throw new IllegalStateException(project.name() + " runs in your IDE. Start it there, or change its run mode.");
        }
        if (current.state() == ServiceState.FAILED) {
            stop(serviceId);   // clean up the dead or stuck group before starting again
        }
        int port = project.devPort();
        if (port > 0 && network.canConnect("localhost", port, Duration.ofMillis(300))) {
            throw new IllegalStateException("Port " + port + " is already in use by "
                    + network.portHolder(port).orElse("another process") + ". Stop it first.");
        }
        // the team's databases and brokers first (docker compose up -d), so the service finds them
        List<String> composeStarted = compose == null ? List.of() : compose.ensureTeamEnvironments();
        LaunchSpec spec = plan(project, settings, cfg);
        spec.pullImage().ifPresent(image -> {
            RegistryLogins.Login login = registryLogins.check(ImageRef.parse(image).registry());
            if (!login.loggedIn()) {
                throw new IllegalStateException(login.detail() + ". Log in under Settings, Container registry "
                        + "(your registry login, which may differ from your git token).");
            }
        });
        Path logFile = paths.serviceLog(serviceId);
        logs.rotateIfLarge(logFile);
        logs.mark(logFile, "start " + settings.runMode());
        composeStarted.forEach(line -> appendToLog(logFile, line));

        Instant now = Instant.now();
        if (settings.runMode() == RunMode.SCRIPT && !Files.isDirectory(project.path().resolve("node_modules"))) {
            // first start of a fresh checkout: npm install, then the script (shown as "Installing the npm packages")
            ProcessRef install = processes.spawn(List.of("npm", "install", "--no-audit", "--no-fund"), project.path(), spec.environment(), logFile);
            state.update(s -> s.with(serviceId, new Run(install.pgid(), install.startedAt(), settings.runMode(), now, port,
                    null, Phase.PULLING, settings.startTimeoutSeconds(), null)));
            Thread.ofVirtual().name("npm-install-" + serviceId).start(() -> installAndSpawn(serviceId, install, project, spec, logFile, settings, port));
            return status(serviceId);
        }
        if (spec.pullImage().isPresent()) {
            state.update(s -> s.with(serviceId, new Run(0, now, settings.runMode(), now, port,
                    spec.containerName().orElse(null), Phase.PULLING, settings.startTimeoutSeconds(), null)));
            Thread.ofVirtual().name("start-" + serviceId).start(() -> pullAndSpawn(serviceId, spec, logFile, settings, port));
        } else {
            spawn(serviceId, spec, logFile, settings, port);
        }
        return status(serviceId);
    }

    private LaunchSpec plan(Project project, ServiceSettings settings, KitoutConfig cfg) {
        Map<String, String> env = environment.resolve(project.id());
        return switch (settings.runMode()) {
            case IMAGE -> LaunchPlanner.image(project, image(project, settings, cfg), settings.runtimeProfile(), env,
                    mounts(project, settings), dockerArgs(project, settings));
            case JVM -> LaunchPlanner.jvm(project, artifacts.jvmArtifact(project).orElseThrow(() -> new IllegalStateException(
                    project.type() == org.kitout.domain.project.ProjectType.QUARKUS
                            ? "No JVM build found for " + project.name() + ". Build it with mode JVM first."
                            : "No runnable jar found for " + project.name() + " (a jar with a Main-Class in " + ArtifactLocator.outputDir(project).getFileName()
                            + "/). Build it first; a plain Java project needs a runnable jar (shade, assembly or the Spring Boot plugin).")),
                    settings.runtimeProfile(), settings.jvmArgs(), env);
            case SCRIPT -> LaunchPlanner.script(project, script(project, settings), project.devPort(), env);
            case NATIVE -> LaunchPlanner.nativeRunner(project, artifacts.nativeRunner(project).orElseThrow(() -> new IllegalStateException(
                    "No native build found for " + project.name() + ". Build it with mode Native first.")),
                    settings.runtimeProfile(), env);
            case IDE -> throw new IllegalStateException("IDE services are not started by kitout");
        };
    }

    /** For views: the script a Node project starts with. */
    public static String scriptOf(Project project, ServiceSettings settings) {
        return script(project, settings);
    }

    /** The npm script a Node project starts with: the developer's choice, else {@code dev}, else {@code start}, else the first. */
    static String script(Project project, ServiceSettings settings) {
        if (settings.script() != null) {
            return settings.script();
        }
        for (String preferred : List.of("dev", "start", "serve")) {
            if (project.scripts().contains(preferred)) {
                return preferred;
            }
        }
        if (project.scripts().isEmpty()) {
            throw new IllegalStateException(project.name() + " has no npm script to start it (dev or start in package.json)");
        }
        return project.scripts().get(0);
    }

    /**
     * The service's host folders as {@code --mount} values. A host folder must exist: docker would
     * otherwise refuse to start (with --mount) and the reason would only be in the log.
     */
    List<String> mounts(Project project, ServiceSettings settings) {
        return settings.volumes().stream().map(v -> {
            Path host = paths.expand(v.hostPath());
            if (!Files.exists(host)) {
                throw new IllegalStateException("The folder " + host + " mounted into " + project.name() + " (as " + v.containerPath()
                        + ") does not exist. Create it, or change the volume under Docker options in the service row.");
            }
            return DockerOptions.mount(host.toAbsolutePath().normalize().toString(), v.containerPath(), v.readOnly());
        }).toList();
    }

    /** The service's own docker run options; checked again here, since config.json can be edited by hand. */
    static List<String> dockerArgs(Project project, ServiceSettings settings) {
        try {
            return DockerOptions.parse(settings.dockerArgs());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("The docker options of " + project.name() + " cannot be used: " + e.getMessage(), e);
        }
    }

    /**
     * The image to run: the repository (derived from the git origin, or the override) plus the
     * tag the developer chose. There is deliberately no default tag.
     *
     * @throws IllegalStateException when no repository is known or no tag is chosen yet
     */
    /**
     * The settings with a run mode this project's type offers: a mode it does not have (e.g. the
     * team default "jvm" for a Node project, or for an image-only service) becomes the type's own
     * (an npm script, the registry image). Choosing such a mode on purpose is refused by the API.
     */
    public static ServiceSettings fit(Project project, ServiceSettings settings) {
        return project.type().runModes().contains(settings.runMode()) ? settings
                : settings.withRunMode(project.type().defaultMode(settings.runMode()));
    }

    public String image(Project project, ServiceSettings settings, KitoutConfig cfg) {
        if (needsTag(settings)) {
            throw new IllegalStateException("Choose an image tag for " + project.name()
                    + ": the tag next to 'Registry image' on the Services page, or 'kitout tag " + project.id() + "'.");
        }
        if (settings.imageTag() != null) {
            return ImageRef.parse(imageRepository(project, settings, cfg)).withTag(settings.imageTag());
        }
        return settings.image().strip();   // an override with its own tag, from before tags were chosen separately
    }

    /** True while no tag is chosen: neither a tag nor an image override that names one. */
    public static boolean needsTag(ServiceSettings settings) {
        return settings.imageTag() == null
                && (settings.image() == null || ImageRef.parse(settings.image()).tag().isEmpty());
    }

    /** The image repository without tag, e.g. {@code registry.example.com/acme/shop/orders}. */
    public String imageRepository(Project project, ServiceSettings settings, KitoutConfig cfg) {
        if (settings.image() != null && !settings.image().isBlank()) {
            return ImageRef.parse(settings.image()).repository();
        }
        String registry = cfg.gitlab().registryHost();
        if (project.image().isPresent()) {   // an image-only service: the team file names its repository
            String repo = project.image().get();
            boolean hasHost = repo.contains("/") && (repo.substring(0, repo.indexOf('/')).contains(".") || repo.substring(0, repo.indexOf('/')).contains(":"));
            if (hasHost) {
                return repo;
            }
            if (registry.isEmpty()) {
                throw new IllegalStateException("Set the container registry in Settings: " + project.name() + " runs from " + repo + " there.");
            }
            return registry + "/" + repo;
        }
        if (project.identity().isEmpty() || registry.isEmpty()) {
            throw new IllegalStateException("No image known for " + project.name()
                    + ". Set the container registry in Settings, or an image for this service.");
        }
        return registry + "/" + ImagePath.render(cfg.gitlab().imagePath(), project.identity().get());
    }

    /** Waits for {@code npm install} (its process group), then starts the script; a stop in between ends both. */
    private void installAndSpawn(String serviceId, ProcessRef install, Project project, LaunchSpec spec, Path logFile,
                                 ServiceSettings settings, int port) {
        try {
            Instant until = Instant.now().plus(MAX_PULL);
            while (processes.isAlive(install) && Instant.now().isBefore(until)) {
                Thread.sleep(500);
            }
            if (!state.load().run(serviceId).map(r -> r.phase() == Phase.PULLING).orElse(false)) {
                return;   // stopped while installing
            }
            if (!Files.isDirectory(project.path().resolve("node_modules"))) {
                appendToLog(logFile, "npm install did not finish; see above. Fix it, then start " + project.name() + " again.");
                state.update(s -> s.without(serviceId));
                return;
            }
            appendToLog(logFile, "Installed the npm packages");
            spawn(serviceId, spec, logFile, settings, port);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            appendToLog(logFile, "Start failed: " + Redactor.redact(String.valueOf(e.getMessage())));
        }
    }

    private void pullAndSpawn(String serviceId, LaunchSpec spec, Path logFile, ServiceSettings settings, int port) {
        try {
            String image = spec.pullImage().orElseThrow();
            Result pull = docker.pull(image);
            appendToLog(logFile, pull.succeeded() ? "Pulled " + image : pullFailure(image, pull.output()));
            if (!state.load().run(serviceId).map(r -> r.phase() == Phase.PULLING).orElse(false)) {
                return;   // stopped while pulling
            }
            spec.containerName().filter(docker::containerExists).ifPresent(docker::removeLeftover);
            spawn(serviceId, spec, logFile, settings, port);
            if (pull.succeeded()) {
                pruneOldVersions(image, logFile);
            }
        } catch (RuntimeException e) {
            appendToLog(logFile, "Start failed: " + Redactor.redact(String.valueOf(e.getMessage())));
            LOG.debugf(e, "Start of %s failed", serviceId);
        }
    }

    /**
     * Keeps the local versions of this service's image within the setting (Settings, Docker): the
     * newest ones stay, and every image a container uses; nothing is forced.
     */
    void pruneOldVersions(String image, Path logFile) {
        try {
            String repository = ImageRef.parse(image).repository();
            int keep = config.load().docker().keepImageVersions();
            java.util.Set<String> inUse = new java.util.HashSet<>(docker.imagesOfContainers());
            List<String> remove = org.kitout.domain.run.ImageRetention.toRemove(repository, docker.imageVersions(repository), keep, inUse);
            int removed = 0;
            for (String ref : remove) {
                if (docker.removeImage(ref).succeeded()) {
                    removed++;
                }
            }
            if (removed > 0) {
                appendToLog(logFile, "Removed " + removed + " older version(s) of " + repository + "; keeping the newest " + keep);
            }
        } catch (RuntimeException e) {
            LOG.debugf(e, "Could not tidy old versions of %s", image);
        }
    }

    /** One line for the service log that says what to do, not only what docker said. */
    static String pullFailure(String image, String output) {
        String lower = output.toLowerCase(java.util.Locale.ROOT);
        String registry = ImageRef.parse(image).registry();
        String hint;
        if (lower.contains("unauthorized") || lower.contains("denied") || lower.contains("authentication required")) {
            hint = "The registry " + registry + " refused the login. Log in again under Settings, Container registry.";
        } else if (lower.contains("manifest unknown") || lower.contains("not found")) {
            hint = "The tag " + ImageRef.parse(image).tag().orElse("?") + " does not exist (any more). Choose another tag on the Services page.";
        } else {
            hint = "Using the local copy if there is one.";
        }
        return "Could not pull " + image + ". " + hint + " Docker said: " + Redactor.redact(output.strip());
    }

    private void spawn(String serviceId, LaunchSpec spec, Path logFile, ServiceSettings settings, int port) {
        ProcessRef ref = processes.spawn(spec.command(), spec.workingDirectory(), spec.environment(), logFile);
        state.update(s -> s.with(serviceId, new Run(ref.pgid(), ref.startedAt(), settings.runMode(), Instant.now(), port,
                spec.containerName().orElse(null), Phase.STARTED, settings.startTimeoutSeconds(), null)));
        watchUntilReady(serviceId);
    }

    // ---- stop -----------------------------------------------------------------------------

    /** Stops the whole process group: SIGTERM, then SIGKILL after 10 seconds. Safe to call twice. */
    public ServiceStatus stop(String serviceId) {
        Optional<Run> run = state.load().run(serviceId);
        if (run.isEmpty()) {
            return status(serviceId);
        }
        stopping.add(serviceId);
        try {
            Run r = run.get();
            logs.mark(paths.serviceLog(serviceId), "stop");
            state.update(s -> s.without(serviceId));   // also aborts a pull in progress
            if (r.containerName() != null) {
                docker.stop(r.containerName(), (int) STOP_GRACE.toSeconds());
            }
            if (r.pgid() > 0) {
                processes.terminate(new ProcessRef(r.pgid(), r.processStart()), STOP_GRACE);
            }
            if (r.containerName() != null && docker.containerExists(r.containerName())) {
                docker.removeLeftover(r.containerName());
            }
        } finally {
            stopping.remove(serviceId);
        }
        return status(serviceId);
    }

    public ServiceStatus restart(String serviceId) {
        stop(serviceId);
        return start(serviceId);
    }

    private static void appendToLog(Path logFile, String line) {
        try {
            Files.writeString(logFile, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            // ignore
        }
    }
}
