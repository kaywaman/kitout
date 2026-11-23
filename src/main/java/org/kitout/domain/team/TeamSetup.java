package org.kitout.domain.team;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The team's shared setup, read from {@code kitout-team.json} in a GitLab repository. Everything a
 * newcomer would otherwise have to be told: GitLab and registry, the services (one repository
 * each), groups, compose environments, presets, variables, and the requirements every developer
 * machine must meet. See {@code docs/team/kitout-team.example.json}.
 */
public record TeamSetup(
        int schemaVersion,
        String team,
        String minKitoutVersion,
        GitLab gitlab,
        Registry registry,
        Requirements requirements,
        List<Service> services,
        ServiceDefaults serviceDefaults,
        Map<String, String> variables,
        List<String> secrets,
        List<Group> groups,
        List<ComposeEnvironment> compose,
        List<Preset> presets,
        Build build,
        Doctor doctor,
        List<Repository> otherRepositories,
        List<ImageService> images) {

    public static final int SCHEMA_VERSION = 1;

    public TeamSetup {
        images = images == null ? List.of() : List.copyOf(images);
    }

    /**
     * A service the team runs only from its registry image: no checkout, any language (another
     * team's service, a Node or Python service). It starts with its group, with its tag picker,
     * environment, folders and health switch, like any service in registry image mode.
     *
     * @param name       its service id and the name groups use, e.g. {@code payments}
     * @param image      its repository, relative to the registry ({@code other-team/payments}) or with a host; no tag
     * @param port       the port it answers on (host networking)
     * @param healthPath its readiness check, e.g. {@code /q/health/ready}; null: started when the port answers
     */
    public record ImageService(String name, String image, int port, String healthPath, String description) {

        /** The service id: the name, as kitout names services. */
        public String id() {
            return name.toLowerCase().replaceAll("[^a-z0-9._-]+", ".");
        }
    }

    /**
     * A repository the team needs that is not a service: cloned and pulled like the others, never
     * scanned, listed or started as a service.
     *
     * @param kind {@link #REPOSITORY} (e.g. the one with the compose files), {@link #JAVA_LIBRARY} (a common Java
     *             library the services build against) or {@link #UI_LIBRARY} (a common UI library)
     */
    public record Repository(String repo, String description, String kind) {

        public static final String REPOSITORY = "repository";
        public static final String JAVA_LIBRARY = "java-library";
        public static final String UI_LIBRARY = "ui-library";

        public Repository {
            kind = kind == null || kind.isBlank() ? REPOSITORY : kind;
        }

        public Repository(String repo, String description) {
            this(repo, description, REPOSITORY);
        }

        public boolean library() {
            return kind.equals(JAVA_LIBRARY) || kind.equals(UI_LIBRARY);
        }
    }

    /**
     * The git server: the team file's {@code git} section ({@code gitlab} in older files).
     *
     * @param provider {@code gitlab}, {@code github}, {@code git}, or empty: guessed from the host name
     */
    public record GitLab(String host, String cloneProtocol, String workspaceRoot, String provider) {

        public GitLab(String host, String cloneProtocol, String workspaceRoot) {
            this(host, cloneProtocol, workspaceRoot, "");
        }
    }

    public record Registry(String host, String imagePath) {
    }

    /** @param enforcement {@code block} (default): a failed check stops kitout; {@code warn}: it only warns */
    public record Requirements(String enforcement, List<Check> checks) {

        public boolean blocks() {
            return !"warn".equals(enforcement);
        }
    }

    /**
     * One requirement of the developer machine. kitout knows how to read each {@code id}; the team
     * file says only what is required and how to fix it, never which command to run.
     *
     * @param distribution for {@code os}: e.g. {@code ubuntu}
     * @param vendors      for {@code java}: allowed distributions, e.g. Temurin, OpenJDK; empty for any
     * @param enforceFrom  until this day a failure only warns, so a raised requirement can be announced
     */
    public record Check(String id, String name, String require, String distribution, List<String> vendors, String fix,
                        Optional<LocalDate> enforceFrom) {
    }

    /**
     * One microservice, one repository. Unset values come from {@link ServiceDefaults}.
     *
     * @param healthCheck false: the service counts as started when its port answers (e.g. a BFF whose health
     *                    endpoint is blocked); null: kitout's default, the readiness check
     */
    public record Service(String repo, String runMode, String runtimeProfile, Integer startTimeoutSeconds, String imagePath,
                         Map<String, String> variables, List<String> secrets, Boolean healthCheck) {

        public Service(String repo, String runMode, String runtimeProfile, Integer startTimeoutSeconds, String imagePath,
                       Map<String, String> variables, List<String> secrets) {
            this(repo, runMode, runtimeProfile, startTimeoutSeconds, imagePath, variables, secrets, null);
        }
    }

    public record ServiceDefaults(String runMode, String runtimeProfile, int startTimeoutSeconds) {
    }

    /**
     * @param members and {@code bffs} are repository paths
     * @param bffs    the group's entry points, started together once the other members are up; empty for none
     * @param needs   compose environments the group depends on
     */
    public record Group(String name, List<String> members, List<String> bffs, boolean sequentialStart, List<String> needs) {

        public Group {
            bffs = bffs == null ? List.of() : List.copyOf(bffs);
        }
    }

    /** A file named by repository and path inside it, so it works wherever the repository is cloned. */
    /**
     * A compose file: by repository and path inside it (works wherever the repository is cloned), or,
     * for a team that has no repository for its infrastructure yet, a local {@code file} below the
     * home folder ({@code ~/infra/docker-compose-dev.yml}); then {@code repo} and {@code path} are null.
     */
    public record FileRef(String repo, String path, String file) {

        public FileRef(String repo, String path) {
            this(repo, path, null);
        }

        public boolean local() {
            return file != null;
        }
    }

    /** @param autoStart kitout runs {@code docker compose up -d} for it on start and before services start (default true) */
    public record ComposeEnvironment(String name, List<FileRef> files, List<String> profiles, String description, boolean autoStart) {

        public ComposeEnvironment(String name, List<FileRef> files, List<String> profiles, String description) {
            this(name, files, profiles, description, true);
        }
    }

    public record Preset(String name, String group, List<String> compose, Map<String, String> runModes, String otherServices) {
    }

    public record Build(List<String> profiles, Boolean nativeContainerBuild) {
    }

    public record Doctor(List<String> tokenScopes, Optional<LocalDate> embeddedCredentialsAsErrorFrom) {
    }

    /** All repositories the team file names: services, other repositories, and those with compose files. */
    /** True when the team file names no GitLab: everything stays on this machine (no cloning, no pulling). */
    public boolean localOnly() {
        return gitlab == null || gitlab.host() == null || gitlab.host().isBlank();
    }

    public List<String> repositories() {
        java.util.LinkedHashSet<String> repos = new java.util.LinkedHashSet<>();
        services.forEach(s -> repos.add(s.repo()));
        repos.addAll(nonServiceRepositories().stream().map(Repository::repo).toList());
        return List.copyOf(repos);
    }

    /**
     * Repositories that are not services: those listed under {@code repositories}, the common Java and
     * UI libraries ({@code javaLibraries}, {@code uiLibraries}), and any repository a compose environment
     * takes files from (described as such if not listed).
     */
    public List<Repository> nonServiceRepositories() {
        java.util.LinkedHashMap<String, Repository> result = new java.util.LinkedHashMap<>();
        otherRepositories.forEach(r -> result.put(r.repo().toLowerCase(java.util.Locale.ROOT), r));
        for (ComposeEnvironment c : compose) {
            for (FileRef f : c.files()) {
                if (f.local()) {
                    continue;
                }
                String key = f.repo().toLowerCase(java.util.Locale.ROOT);
                if (service(f.repo()).isEmpty() && !result.containsKey(key)) {
                    result.put(key, new Repository(f.repo(), "Compose files for " + c.name()));
                }
            }
        }
        return List.copyOf(result.values());
    }

    public Optional<Service> service(String repo) {
        return services.stream().filter(s -> s.repo().equalsIgnoreCase(repo)).findFirst();
    }
}
