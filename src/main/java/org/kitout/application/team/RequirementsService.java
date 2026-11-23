package org.kitout.application.team;

import org.kitout.application.KitoutPaths;
import org.kitout.application.env.EnvironmentService;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.domain.project.Project;
import org.kitout.domain.team.TeamSetup;
import org.kitout.domain.team.TeamSetup.Check;
import org.kitout.domain.team.VersionRule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Checks the developer machine against the team file's requirements. It runs on every kitout call
 * (CLI command, and the daemon before each action), so readings are kept while the tool behind them
 * is unchanged: an upgrade, a different {@code java} on PATH or a new {@code JAVA_HOME} is noticed
 * at once, while an unchanged machine costs a few file lookups.
 */
@ApplicationScoped
public class RequirementsService {

    private static final Logger LOG = Logger.getLogger(RequirementsService.class);
    private static final Duration MEMO = Duration.ofSeconds(2);

    public enum Outcome { PASS, WARN, FAIL }

    /**
     * @param required the rule as written in the team file
     * @param found    what this machine has; empty if nothing was found
     * @param fix      what to do, from the team file or kitout's own advice
     * @param note     why it is only a warning (enforced later, or the team chose to warn)
     */
    public record Result(String id, String name, String required, String found, String detail, Outcome outcome,
                         String problem, String fix, String note) {
    }

    /**
     * @param configured false when no team file is in use: nothing to check
     * @param blocked    true when a failed requirement stops kitout
     */
    public record Report(boolean configured, String team, List<Result> results, boolean blocked, Instant checkedAt) {

        public static Report none() {
            return new Report(false, "", List.of(), false, Instant.now());
        }

        public List<Result> failures() {
            return results.stream().filter(r -> r.outcome() == Outcome.FAIL).toList();
        }

        public List<Result> warnings() {
            return results.stream().filter(r -> r.outcome() == Outcome.WARN).toList();
        }
    }

    private final TeamService team;
    private final MachineProbe probe;
    private final WorkspaceService workspace;
    private final EnvironmentService environment;
    private final KitoutPaths paths;
    private final Clock clock;
    private volatile Report last;

    @Inject
    public RequirementsService(TeamService team, MachineProbe probe, WorkspaceService workspace, EnvironmentService environment, KitoutPaths paths) {
        this(team, probe, workspace, environment, paths, Clock.systemDefaultZone());
    }

    RequirementsService(TeamService team, MachineProbe probe, WorkspaceService workspace, EnvironmentService environment, KitoutPaths paths,
                        Clock clock) {
        this.team = team;
        this.probe = probe;
        this.workspace = workspace;
        this.environment = environment;
        this.paths = paths;
        this.clock = clock;
    }

    /** The current report; checks again unless the last check is a moment old. */
    public Report check() {
        Report r = last;
        if (r != null && r.checkedAt().plus(MEMO).isAfter(clock.instant())) {
            return r;
        }
        return check(false);
    }

    /** @param force read every tool again, ignoring kept readings */
    public synchronized Report check(boolean force) {
        Optional<TeamSetup> setup = team.status().setup();
        if (setup.isEmpty() || setup.get().requirements() == null || setup.get().requirements().checks().isEmpty()) {
            last = setup.map(s -> new Report(true, s.team(), secretWarnings(), false, clock.instant())).orElseGet(Report::none);
            return last;
        }
        TeamSetup s = setup.get();
        List<Path> projects = projects();
        Properties kept = force ? new Properties() : loadKept();
        Properties now = new Properties();
        LocalDate today = LocalDate.now(clock);
        List<Result> results = new ArrayList<>();
        for (Check check : s.requirements().checks()) {
            String key = check.id() + "|" + check.distribution() + "|" + check.vendors();
            String fingerprint = key + "#" + probe.fingerprint(check.id(), projects);
            MachineProbe.Reading reading = kept(kept, check.id(), fingerprint)
                    .orElseGet(() -> probe.read(check.id(), Optional.ofNullable(check.distribution()), check.vendors() == null ? List.of() : check.vendors(), projects));
            keep(now, check.id(), fingerprint, reading);
            results.add(evaluate(check, reading, today, s.requirements().blocks()));
            if ("maven".equals(check.id())) {
                results.addAll(wrappers(check, projects, today, s.requirements().blocks()));
            }
        }
        results.addAll(secretWarnings());
        store(now);
        boolean blocked = results.stream().anyMatch(r -> r.outcome() == Outcome.FAIL);
        last = new Report(true, s.team(), List.copyOf(results), blocked, clock.instant());
        return last;
    }

    /** Problems first (failed, then warnings), then what passed; stable within each. */
    public static List<Result> ordered(List<Result> results) {
        List<Result> sorted = new ArrayList<>(results);
        sorted.sort(java.util.Comparator.comparingInt(r -> r.outcome() == Outcome.FAIL ? 0 : r.outcome() == Outcome.WARN ? 1 : 2));
        return sorted;
    }

    // ---- evaluation (pure, tested) ----------------------------------------------------------------

    static Result evaluate(Check check, MachineProbe.Reading reading, LocalDate today, boolean blocks) {
        String name = check.name() != null && !check.name().isBlank() ? check.name() : defaultName(check.id());
        VersionRule rule = VersionRule.parse(check.require() == null ? "*" : check.require());
        String problem = reading.problem();
        if (problem == null && !rule.matches(reading.found())) {
            problem = reading.found().isEmpty() ? name + " was not found" : name + " " + reading.found() + " does not meet " + check.require();
        }
        String fix = check.fix() != null && !check.fix().isBlank() ? check.fix() : defaultFix(check.id(), check.require());
        if (problem == null) {
            return new Result(check.id(), name, check.require(), reading.found(), reading.detail(), Outcome.PASS, null, fix, null);
        }
        if (check.enforceFrom().isPresent() && today.isBefore(check.enforceFrom().get())) {
            return new Result(check.id(), name, check.require(), reading.found(), reading.detail(), Outcome.WARN, problem, fix,
                    "Required from " + check.enforceFrom().get() + "; until then kitout only warns.");
        }
        if (!blocks) {
            return new Result(check.id(), name, check.require(), reading.found(), reading.detail(), Outcome.WARN, problem, fix,
                    "The team file sets requirements to warn only.");
        }
        return new Result(check.id(), name, check.require(), reading.found(), reading.detail(), Outcome.FAIL, problem, fix, null);
    }

    /** Projects build with their own {@code ./mvnw}: each wrapper's Maven must meet the rule too. */
    private List<Result> wrappers(Check check, List<Path> projects, LocalDate today, boolean blocks) {
        List<Result> results = new ArrayList<>();
        VersionRule rule = VersionRule.parse(check.require() == null ? "*" : check.require());
        for (Map.Entry<String, String> w : probe.mavenWrappers(projects).entrySet()) {
            if (rule.matches(w.getValue())) {
                continue;
            }
            Check wrapperCheck = new Check("maven-wrapper", "Maven wrapper of " + w.getKey(), check.require(), null, List.of(),
                    "In " + w.getKey() + ", run: ./mvnw wrapper:wrapper -Dmaven=<a version that meets " + check.require()
                            + ">, then commit .mvn/wrapper/maven-wrapper.properties",
                    check.enforceFrom());
            results.add(evaluate(wrapperCheck, new MachineProbe.Reading(w.getValue(), "./mvnw in " + w.getKey(), null), today, blocks));
        }
        return results;
    }

    /** Team secrets not entered yet: services that need them will not start, but nothing else is stopped. */
    private List<Result> secretWarnings() {
        return environment.missingTeamSecrets().stream()
                .map(name -> new Result("secret:" + name, "Your " + name, "set", "", "", Outcome.WARN, name + " is not entered yet",
                        "Enter it in Settings, Team setup, or run: kitout team secret " + name.replace(" (", " --service ").replace(")", ""), "Services that need it will not start until then."))
                .toList();
    }

    static String defaultName(String id) {
        return switch (id) {
            case "os" -> "Operating system";
            case "arch" -> "Processor architecture";
            case "java" -> "Java";
            case "maven" -> "Maven";
            case "docker" -> "Docker Engine";
            case "docker-compose" -> "Docker Compose";
            case "node" -> "Node.js";
            case "git" -> "git";
            case "memory" -> "Memory (MB)";
            default -> id;
        };
    }

    static String defaultFix(String id, String require) {
        return switch (id) {
            case "os" -> "Use the operating system the team requires (" + require + "). Upgrading or replacing the OS is your team's call; ask before you do it.";
            case "arch" -> "kitout and the team's images need a " + require + " machine.";
            case "java" -> "Install a matching JDK (e.g. sudo apt install temurin-21-jdk from the Adoptium repository, or SDKMAN: sdk install java 21-tem), "
                    + "then point PATH and JAVA_HOME to it (sudo update-alternatives --config java).";
            case "maven" -> "Install a matching Maven (SDKMAN: sdk install maven <version>), or remove the system mvn and build with each project's ./mvnw.";
            case "docker" -> "Install Docker Engine from Docker's apt repository (docs.docker.com/engine/install/ubuntu), "
                    + "or pin it: sudo apt install docker-ce=<version> docker-ce-cli=<version>.";
            case "docker-compose" -> "Install or update the compose plugin: sudo apt install docker-compose-plugin.";
            case "node" -> "Install a matching Node.js (nvm install <version> && nvm alias default <version>). "
                    + "If you use nvm, start kitout from a shell where nvm is loaded.";
            case "git" -> "Update git: sudo add-apt-repository ppa:git-core/ppa && sudo apt install git.";
            case "memory" -> "This machine has less memory than the team requires for running the services.";
            default -> "See your team's setup notes.";
        };
    }

    // ---- kept readings ---------------------------------------------------------------------------

    private static Optional<MachineProbe.Reading> kept(Properties kept, String id, String fingerprint) {
        if (!fingerprint.equals(kept.getProperty(id + ".fingerprint")) || fingerprint.endsWith("#")) {
            return Optional.empty();   // arch and memory have no fingerprint: read every time
        }
        String problem = kept.getProperty(id + ".problem");
        return Optional.of(new MachineProbe.Reading(kept.getProperty(id + ".found", ""), kept.getProperty(id + ".detail", ""),
                problem == null || problem.isEmpty() ? null : problem));
    }

    private static void keep(Properties p, String id, String fingerprint, MachineProbe.Reading r) {
        if (r.problem() != null && r.found().isEmpty()) {
            return;   // a daemon that is down now may be up in a moment: ask again next time
        }
        p.setProperty(id + ".fingerprint", fingerprint);
        p.setProperty(id + ".found", r.found());
        p.setProperty(id + ".detail", r.detail() == null ? "" : r.detail());
        p.setProperty(id + ".problem", r.problem() == null ? "" : r.problem());
    }

    private Path keptFile() {
        return paths.stateDir().resolve("team").resolve("requirements.properties");
    }

    private Properties loadKept() {
        Properties p = new Properties();
        Path file = keptFile();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file)) {
                p.load(r);
            } catch (IOException | IllegalArgumentException e) {
                LOG.debugf("Ignoring kept requirement readings: %s", e.getMessage());
            }
        }
        return p;
    }

    private void store(Properties p) {
        Path file = keptFile();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("requirements.properties.tmp");
            try (Writer w = Files.newBufferedWriter(tmp)) {
                p.store(w, "kitout: machine readings, kept while the tools are unchanged. Safe to delete.");
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.debugf("Could not keep requirement readings: %s", e.getMessage());
        }
    }

    private List<Path> projects() {
        try {
            return workspace.projects().stream().filter(p -> p.buildTool().java()).map(Project::path).toList();   // their Maven wrappers
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
