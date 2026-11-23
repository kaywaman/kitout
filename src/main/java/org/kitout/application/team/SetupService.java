package org.kitout.application.team;

import org.kitout.application.clone.CloneService;
import org.kitout.application.config.ConfigRepository;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.env.EnvironmentService;
import org.kitout.application.git.GitSyncService;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.domain.team.TeamSetup;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The first-call walk-through, as one report the UI and the CLI show alike: the team file and every
 * check made on it, the machine against its requirements, the developer's secrets, and the team's
 * repositories (configured, on disk, or still to clone). Loading and cloning are limited to what the
 * team file names ({@link TeamScope}).
 */
@ApplicationScoped
public class SetupService {

    /**
     * CONFIGURED: loaded; LOCAL: on this machine, ready to load; MISSING: to clone;
     * NOT_LOADABLE: on this machine, but kitout cannot run it as a service (the note says why);
     * REMOVED: the developer removed it from kitout earlier (Settings, Removed projects).
     * The last two do not hold up the setup: they are shown with what to do.
     */
    public enum ProjectState { CONFIGURED, LOCAL, MISSING, NOT_LOADABLE, REMOVED }

    /** One check made on the team file; {@code problems} are why it failed (empty when it passed). */
    public record FileCheck(String id, String name, List<String> problems) {

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    /**
     * @param kind {@code service}; or, never a service: {@code repository} (needed by the team, e.g. compose files),
     *             {@code java-library} or {@code ui-library} (common code the services build against)
     * @param description for a repository or library: what it is for
     */
    public record Project(String repo, String serviceId, String kind, ProjectState state, String path, String description) {

        public boolean service() {
            return kind.equals("service");
        }

        /** Still something to do in step 3. */
        public boolean open() {
            return !service() ? state == ProjectState.MISSING : state == ProjectState.LOCAL || state == ProjectState.MISSING;
        }
    }

    /**
     * @param complete everything is in place: file valid, machine meets the requirements, secrets entered, repositories loaded
     * @param ready    the machine check passed, so projects may be loaded or cloned
     */
    /**
     * @param canCreate a developer without a team file may build one here (not when kitout's build requires the team's file)
     * @param editable  the team file in use is the one kitout wrote on this machine: services can be added to it
     * @param localOnly the team file names no GitLab: nothing to clone or pull
     * @param suggested what the form to follow a team file starts with: the preset of kitout's build, or just this
     *                  developer's GitLab host; empty if neither
     * @param presetProblem why the preset of kitout's build cannot be used; empty if it can
     */
    public record Setup(TeamService.Status team, Optional<String> defaultLocation, List<FileCheck> fileChecks,
                        RequirementsService.Report requirements, List<String> missingSecrets, List<Project> projects,
                        String workspaceRoot, boolean ready, boolean complete, boolean canCreate, boolean editable, boolean localOnly,
                        Optional<KitoutConfig.TeamSettings> suggested, Optional<String> presetProblem) {
    }

    /** The checks made on the team file, in the order a person reads them; problems are sorted into them by their wording. */
    static final Map<String, String> FILE_CHECKS = new LinkedHashMap<>();

    static {
        FILE_CHECKS.put("read", "Read the file");
        FILE_CHECKS.put("format", "Valid JSON, schema version 1");
        FILE_CHECKS.put("version", "This kitout is new enough (minKitoutVersion)");
        FILE_CHECKS.put("placeholders", "Every <<REPLACE: …>> replaced");
        FILE_CHECKS.put("credentials", "No passwords or tokens in the file");
        FILE_CHECKS.put("services", "Services: one repository each, no duplicates");
        FILE_CHECKS.put("groups", "Groups: members and BFFs are services");
        FILE_CHECKS.put("compose", "Compose environments: files named by repository");
        FILE_CHECKS.put("variables", "Variables and secret names");
        FILE_CHECKS.put("requirements", "Machine requirements: known checks, valid rules");
    }

    private final TeamService team;
    private final RequirementsService requirements;
    private final EnvironmentService environment;
    private final CloneService clones;
    private final WorkspaceService workspace;
    private final ConfigRepository config;
    private final GitSyncService git;
    private final RepositoryLocator locator;
    private final TeamCreator creator;

    public SetupService(TeamService team, RequirementsService requirements, EnvironmentService environment, CloneService clones,
                        WorkspaceService workspace, ConfigRepository config, GitSyncService git, RepositoryLocator locator,
                        TeamCreator creator) {
        this.creator = creator;
        this.git = git;
        this.locator = locator;
        this.team = team;
        this.requirements = requirements;
        this.environment = environment;
        this.clones = clones;
        this.workspace = workspace;
        this.config = config;
    }

    /** @param recheck read every tool again (the "Check again" after a fix) */
    public Setup status(boolean recheck) {
        // "Check again" also reads the team file again: after a fix in GitLab, the next check must see it
        TeamService.Status status = recheck && team.status().configured() ? team.refresh() : team.status();
        RequirementsService.Report report = recheck ? requirements.check(true) : requirements.check();
        List<FileCheck> checks = status.configured() ? fileChecks(status) : List.of();
        List<String> missing = status.setup().isPresent() ? environment.missingTeamSecrets() : List.of();
        KitoutConfig cfg = config.load();
        List<Project> projects = status.setup().map(t -> projects(t, cfg)).orElse(List.of());
        boolean ready = status.setup().isPresent() && !report.blocked();
        boolean complete = ready && missing.isEmpty() && projects.stream().noneMatch(Project::open);
        return new Setup(status, team.defaultLocation(), checks, report, missing, projects, cfg.gitlab().workspaceRoot(), ready, complete,
                creator.allowed(), creator.editable(), status.setup().map(TeamSetup::localOnly).orElse(false),
                team.preset().or(() -> cfg.gitlab().host().isBlank() ? Optional.empty()
                        : Optional.of(new KitoutConfig.TeamSettings(cfg.gitlab().host(), null, null, null, null))),
                team.presetProblem());
    }

    /**
     * Loads the team's projects that are already on this machine: scans the clone folder and the
     * workspace folders; projects the team file does not name are left out.
     */
    public WorkspaceService.ScanResult loadLocal() {
        requireReady();
        KitoutConfig cfg = config.load();
        List<String> roots = new ArrayList<>(cfg.workspaceRoots());
        if (!cfg.gitlab().workspaceRoot().isBlank() && !roots.contains(cfg.gitlab().workspaceRoot())) {
            roots.add(cfg.gitlab().workspaceRoot());
        }
        return workspace.scan(roots);
    }

    /** Starts cloning the team's repositories that are not on this machine; afterwards, {@link #loadLocal()} adds them. */
    public CloneService.Job cloneMissing(boolean light) {
        requireReady();
        TeamSetup setup = team.status().setup().orElseThrow();
        if (setup.localOnly()) {
            throw new IllegalStateException("The team file names no git host, so there is nothing to clone: add git.host when your projects are on a server");
        }
        List<CloneService.Selection> missing = clones.repositories(setup.repositories()).stream().filter(c -> !c.alreadyLocal())
                .map(c -> new CloneService.Selection(c.fullPath(), light)).toList();
        if (missing.isEmpty()) {
            throw new IllegalStateException("All of the team's repositories are on this machine; load them instead");
        }
        return clones.clone(missing);
    }

    /** The team's repositories that are not services (libraries included), with where they are (or will be) on this machine. */
    public List<Project> repositories() {
        return team.status().setup().map(t -> projects(t, config.load()).stream().filter(p -> !p.service()).toList())
                .orElse(List.of());
    }

    /** Clones one of the team's repositories that are not services. */
    public CloneService.Job cloneRepository(String repo, boolean light) {
        requireReady();
        Project p = repository(repo);
        if (p.state() != ProjectState.MISSING) {
            throw new IllegalStateException(repo + " is already at " + p.path());
        }
        clones.repositories(List.of(p.repo()));
        return clones.clone(List.of(new CloneService.Selection(p.repo(), light)));
    }

    /** Pulls one of the team's repositories that are not services: fast-forward only, never over local changes. */
    public GitSyncService.PullOutcome pullRepository(String repo) {
        requireReady();
        Project p = repository(repo);
        if (p.state() == ProjectState.MISSING) {
            throw new IllegalStateException(repo + " is not on this machine yet: clone it first");
        }
        return git.pullRepository(p.repo(), p.repo().substring(p.repo().lastIndexOf('/') + 1), Path.of(p.path()));
    }

    private Project repository(String repo) {
        return repositories().stream().filter(p -> p.repo().equalsIgnoreCase(repo == null ? "" : repo.strip())).findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException(repo + " is not one of the team's repositories or libraries"));
    }

    private void requireReady() {
        if (team.status().setup().isEmpty()) {
            throw new IllegalStateException("Follow a valid team file first (Settings, Team setup, or: kitout team use <location>)");
        }
        if (requirements.check().blocked()) {
            throw new IllegalStateException("This machine does not meet the team's requirements yet: fix them first (kitout check)");
        }
    }

    static List<FileCheck> fileChecks(TeamService.Status status) {
        Map<String, List<String>> byCheck = new LinkedHashMap<>();
        FILE_CHECKS.keySet().forEach(k -> byCheck.put(k, new ArrayList<>()));
        status.problems().forEach(p -> byCheck.get(category(p)).add(p));
        List<FileCheck> result = new ArrayList<>();
        FILE_CHECKS.forEach((id, name) -> result.add(new FileCheck(id, name, List.copyOf(byCheck.get(id)))));
        return result;
    }

    static String category(String problem) {
        String p = problem.toLowerCase(Locale.ROOT);
        if (p.startsWith("could not read")) {
            return "read";
        }
        if (p.contains("placeholder")) {
            return "placeholders";
        }
        if (p.contains("credential")) {
            return "credentials";
        }
        if (p.contains("needs kitout")) {
            return "version";
        }
        if (p.startsWith("requirements")) {
            return "requirements";
        }
        if (p.startsWith("services") || p.startsWith("servicedefaults")) {
            return "services";
        }
        if (p.startsWith("groups")) {
            return "groups";
        }
        if (p.startsWith("compose")) {
            return "compose";
        }
        if (p.startsWith("variables") || p.startsWith("secrets") || p.contains("environment variable")) {
            return "variables";
        }
        return "format";
    }

    /**
     * A team service whose repository is on this machine but not loaded: ready to load, or the
     * reason it cannot be (so the walk-through never asks for something that cannot happen).
     */
    private Project local(String repo, String id, Path folder, KitoutConfig cfg, TeamSetup t) {
        List<org.kitout.domain.project.Project> found = workspace.projectsIn(folder);
        if (found.isEmpty()) {
            String why = workspace.whyNoService(folder).orElse("no project kitout can run found in it");
            return new Project(repo, id, "service", ProjectState.NOT_LOADABLE, folder.toString(), "Cannot be loaded: " + why
                    + ". If it holds compose files or other infrastructure, the team file should list it under \"repositories\", not \"services\""
                    + " (a common library: under \"javaLibraries\" or \"uiLibraries\").");
        }
        if (found.stream().allMatch(p -> cfg.isHidden(p.path().toString()))) {
            return new Project(repo, id, "service", ProjectState.REMOVED, folder.toString(),
                    "You removed it from kitout earlier. To use it again: Settings, Removed projects, Show again.");
        }
        Optional<org.kitout.domain.project.Project> inScope = found.stream().filter(p -> TeamScope.allows(Optional.of(t), p.identity())).findFirst();
        if (inScope.isEmpty()) {
            String origin = found.get(0).identity().map(i -> i.host() + "/" + i.fullPath()).orElse("none");
            return new Project(repo, id, "service", ProjectState.NOT_LOADABLE, folder.toString(), "Cannot be loaded: its git origin is "
                    + origin + ", but the team file names " + t.gitlab().host() + "/" + repo + ". Fix the origin (git remote set-url origin …) or the team file.");
        }
        return new Project(repo, id, "service", ProjectState.LOCAL, folder.toString(), null);
    }

    private List<Project> projects(TeamSetup t, KitoutConfig cfg) {
        Map<String, String> targets = new LinkedHashMap<>();
        Map<String, Boolean> onDisk = new LinkedHashMap<>();
        try {
            clones.repositories(t.repositories()).forEach(c -> {
                targets.put(c.fullPath(), c.target().toString());
                onDisk.put(c.fullPath(), c.alreadyLocal());
            });
        } catch (RuntimeException e) {
            // no GitLab host yet: everything counts as missing
        }
        for (String repo : t.repositories()) {   // cloned by hand somewhere else: found by its git origin
            if (!onDisk.getOrDefault(repo, false)) {
                try {
                    locator.locate(repo).ifPresent(path -> {
                        onDisk.put(repo, true);
                        targets.put(repo, path.toString());
                    });
                } catch (RuntimeException e) {
                    // not found
                }
            }
        }
        List<Project> result = new ArrayList<>();
        for (TeamSetup.Service s : t.services()) {
            String id = TeamOverlay.serviceId(s.repo());
            KitoutConfig.ServiceSettings configured = cfg.services().get(id);
            if (configured != null && configured.path() != null && Files.isDirectory(Path.of(configured.path()))) {
                result.add(new Project(s.repo(), id, "service", ProjectState.CONFIGURED, configured.path(), null));
            } else if (onDisk.getOrDefault(s.repo(), false)) {
                result.add(local(s.repo(), id, Path.of(targets.get(s.repo())), cfg, t));
            } else {
                result.add(new Project(s.repo(), id, "service", ProjectState.MISSING, targets.getOrDefault(s.repo(), ""), null));
            }
        }
        for (TeamSetup.Repository r : t.nonServiceRepositories()) {
            result.add(new Project(r.repo(), null, r.kind(), onDisk.getOrDefault(r.repo(), false) ? ProjectState.LOCAL : ProjectState.MISSING,
                    targets.getOrDefault(r.repo(), ""), r.description()));
        }
        return result;
    }
}
