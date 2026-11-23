package org.kitout.application.config;

import org.kitout.domain.git.GitHost;

import org.kitout.domain.group.Group;
import org.kitout.domain.run.RunMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the developer configures, stored as {@code ~/.config/kitout/config.json}.
 *
 * <p>Immutable: change it with the {@code with...} methods and save the new instance through
 * {@link ConfigRepository#update}. When adding a field, give it a default in {@link #defaults()}
 * and in the compact constructor (old files do not have it), and bump {@link #CURRENT_SCHEMA}
 * only if existing values must be transformed (see {@code JsonConfigRepository#migrate}).
 */
public record KitoutConfig(
        int schemaVersion,
        List<String> workspaceRoots,
        GitLabSettings gitlab,
        Map<String, ServiceSettings> services,
        List<Group> groups,
        BuildSettings build,
        DoctorSettings doctor,
        List<String> hiddenProjects,
        DockerSettings docker,
        RuntimeSettings runtime,
        TeamSettings team) {

    /**
     * 2: a group has a list of BFFs ({@code bffs}) instead of one ({@code bff}).
     * 3: no change of format (a step that is no longer needed).
     */
    public static final int CURRENT_SCHEMA = 3;

    public KitoutConfig {
        workspaceRoots = workspaceRoots == null ? List.of() : List.copyOf(workspaceRoots);
        gitlab = gitlab == null ? GitLabSettings.defaults() : gitlab;
        services = services == null ? Map.of() : Map.copyOf(services);
        groups = groups == null ? List.of() : List.copyOf(groups);
        build = build == null ? BuildSettings.defaults() : build;
        doctor = doctor == null ? DoctorSettings.defaults() : doctor;
        hiddenProjects = hiddenProjects == null ? List.of() : List.copyOf(hiddenProjects);
        docker = docker == null ? DockerSettings.defaults() : docker;
        runtime = runtime == null ? RuntimeSettings.defaults() : runtime;
        team = team == null ? TeamSettings.none() : team;
    }

    public static KitoutConfig defaults() {
        return new KitoutConfig(CURRENT_SCHEMA, List.of(), GitLabSettings.defaults(), Map.of(), List.of(),
                BuildSettings.defaults(), DoctorSettings.defaults(), List.of(), DockerSettings.defaults(), RuntimeSettings.defaults(), TeamSettings.none());
    }

    /** True if the developer removed this project folder from kitout; scans skip it. */
    public boolean isHidden(String projectPath) {
        return hiddenProjects.contains(projectPath);
    }

    public Optional<Group> group(String name) {
        return groups.stream().filter(g -> g.name().equals(name)).findFirst();
    }

    public ServiceSettings serviceSettings(String serviceId) {
        return services.getOrDefault(serviceId, ServiceSettings.defaults(null));
    }

    public KitoutConfig withService(String serviceId, ServiceSettings settings) {
        Map<String, ServiceSettings> copy = new LinkedHashMap<>(services);
        copy.put(serviceId, settings);
        return new KitoutConfig(schemaVersion, workspaceRoots, gitlab, copy, groups, build, doctor, hiddenProjects, docker, runtime, team);
    }

    public KitoutConfig withServices(Map<String, ServiceSettings> newServices) {
        return new KitoutConfig(schemaVersion, workspaceRoots, gitlab, newServices, groups, build, doctor, hiddenProjects, docker, runtime, team);
    }

    public KitoutConfig withGroups(List<Group> newGroups) {
        return new KitoutConfig(schemaVersion, workspaceRoots, gitlab, services, newGroups, build, doctor, hiddenProjects, docker, runtime, team);
    }

    public KitoutConfig withGroup(Group group) {
        List<Group> copy = new ArrayList<>(groups.stream().filter(g -> !g.name().equals(group.name())).toList());
        copy.add(group);
        return withGroups(copy);
    }

    public KitoutConfig withWorkspaceRoots(List<String> roots) {
        return new KitoutConfig(schemaVersion, roots, gitlab, services, groups, build, doctor, hiddenProjects, docker, runtime, team);
    }

    public KitoutConfig withGitlab(GitLabSettings settings) {
        return new KitoutConfig(schemaVersion, workspaceRoots, settings, services, groups, build, doctor, hiddenProjects, docker, runtime, team);
    }

    public KitoutConfig withBuild(BuildSettings settings) {
        return new KitoutConfig(schemaVersion, workspaceRoots, gitlab, services, groups, settings, doctor, hiddenProjects, docker, runtime, team);
    }

    /**
     * Folders the developer removed from kitout (absolute paths), e.g. libraries or archived
     * projects that are detected as Quarkus projects but are not services.
     */
    public KitoutConfig withHiddenProjects(List<String> paths) {
        return new KitoutConfig(schemaVersion, workspaceRoots, gitlab, services, groups, build, doctor, paths, docker, runtime, team);
    }

    public KitoutConfig withDocker(DockerSettings settings) {
        return new KitoutConfig(schemaVersion, workspaceRoots, gitlab, services, groups, build, doctor, hiddenProjects, settings, runtime, team);
    }

    public KitoutConfig withRuntime(RuntimeSettings settings) {
        return new KitoutConfig(schemaVersion, workspaceRoots, gitlab, services, groups, build, doctor, hiddenProjects, docker, settings, team);
    }

    public KitoutConfig withTeam(TeamSettings settings) {
        return new KitoutConfig(schemaVersion, workspaceRoots, gitlab, services, groups, build, doctor, hiddenProjects, docker, runtime, settings);
    }

    public KitoutConfig withDoctor(DoctorSettings settings) {
        return new KitoutConfig(schemaVersion, workspaceRoots, gitlab, services, groups, build, settings, hiddenProjects, docker, runtime, team);
    }

    /**
     * The git server the team's repositories are on. Named {@code gitlab} in config.json for history; any git server works.
     *
     * @param host           e.g. {@code gitlab.example.com} or {@code github.com}; empty until set up
     * @param cloneProtocol  {@code https} (team standard) or {@code ssh}
     * @param workspaceRoot  where clones go; the namespace is mirrored below it
     * @param registryHost   container registry, e.g. {@code registry.gitlab.example.com} or {@code ghcr.io}; may end with a
     *                       path prefix ({@code images.example.com/team}) that every image repository starts with
     * @param imagePath      where a project's images are inside the registry, as a pattern of {@code {namespace}}
     *                       ({@code acme/shop}), {@code {group}} ({@code acme}), {@code {project}} ({@code orders})
     *                       and {@code {path}} ({@code acme/shop/orders}); default {@code {namespace}/{project}}
     * @param provider       {@code gitlab}, {@code github} or {@code git}; empty: guessed from the host name ({@link GitHost#of})
     */
    public record GitLabSettings(String host, String cloneProtocol, String workspaceRoot, String registryHost, String imagePath,
                                 String provider) {

        public GitLabSettings {
            host = host == null ? "" : host.trim().replaceFirst("^https?://", "").replaceAll("/+$", "");
            cloneProtocol = "ssh".equalsIgnoreCase(cloneProtocol) ? "ssh" : "https";
            workspaceRoot = workspaceRoot == null || workspaceRoot.isBlank() ? "~/work" : workspaceRoot.trim();
            provider = provider == null || provider.isBlank() || provider.strip().equalsIgnoreCase("auto") ? ""
                    : provider.strip().toLowerCase(java.util.Locale.ROOT);
            if (!GitHost.valid(provider)) {
                provider = "";
            }
            registryHost = registryHost == null || registryHost.isBlank()
                    ? GitHost.of(provider, host).defaultRegistry(host)
                    : registryHost.trim().replaceFirst("^https?://", "").replaceAll("/+$", "");
            imagePath = imagePath == null || imagePath.isBlank() ? DEFAULT_IMAGE_PATH : imagePath.strip();
        }

        public GitLabSettings(String host, String cloneProtocol, String workspaceRoot, String registryHost, String imagePath) {
            this(host, cloneProtocol, workspaceRoot, registryHost, imagePath, "");
        }

        public static final String DEFAULT_IMAGE_PATH = "{namespace}/{project}";

        public static GitLabSettings defaults() {
            return new GitLabSettings("", "https", "~/work", "", DEFAULT_IMAGE_PATH, "");
        }

        public boolean isConfigured() {
            return !host.isEmpty();
        }

        /** The kind of server: as set, or guessed from the host name. */
        public GitHost kind() {
            return GitHost.of(provider, host);
        }

        /** The clone URL of {@code repo} ({@code group/project}) with this developer's protocol. */
        public String cloneUrl(String repo) {
            return cloneProtocol.equals("ssh") ? GitHost.sshUrl(host, repo) : GitHost.httpsUrl(host, repo);
        }

        public GitLabSettings withProvider(String value) {
            return new GitLabSettings(host, cloneProtocol, workspaceRoot, registryHost, imagePath, value);
        }
    }

    /**
     * Per-service choices. Everything else about a service is detected from its repository.
     *
     * @param path                where the repository is on disk (set by the scanner)
     * @param image               overrides the image repository derived from the git origin; null for the default
     * @param imageTag            the tag to run in IMAGE mode, e.g. {@code mr-123} or {@code 1.4.2}; null until chosen.
     *                            There is no default: {@code latest} rarely fits the task, so the developer picks one
     * @param runtimeProfile      Quarkus profile the service runs with, {@code dev} by default
     * @param startTimeoutSeconds a start that takes longer is marked failed
     * @param volumes             host folders mounted into the container in IMAGE mode
     * @param dockerArgs          extra {@code docker run} options in IMAGE mode, as typed (e.g. {@code --memory 1g --add-host db:10.0.0.5});
     *                            split into arguments without a shell, checked by {@code DockerOptions}. Null when none
     * @param healthCheck         ask the readiness check ({@code /q/health/ready}); false: the service counts as started when its
     *                            port answers (e.g. a BFF whose health endpoint is blocked). Null: not chosen, the team's value,
     *                            else on for projects that have a health endpoint (Quarkus, Spring Boot)
     * @param port                the port the service answers on, instead of the one read from its config: for plain Java
     *                            apps (which have no convention) and Node dev servers; null for the detected one
     * @param script              a Node project's npm script to start it; null: {@code dev}, else {@code start}
     */
    public record ServiceSettings(String path, RunMode runMode, String image, String runtimeProfile,
                                  int startTimeoutSeconds, List<String> jvmArgs, String imageTag, List<EnvVar> environment,
                                  List<VolumeMount> volumes, String dockerArgs, Boolean healthCheck, Integer port, String script) {

        public ServiceSettings {
            runMode = runMode == null ? RunMode.IMAGE : runMode;
            runtimeProfile = runtimeProfile == null || runtimeProfile.isBlank() ? "dev" : runtimeProfile;
            startTimeoutSeconds = startTimeoutSeconds <= 0 ? 120 : startTimeoutSeconds;
            jvmArgs = jvmArgs == null ? List.of() : List.copyOf(jvmArgs);
            imageTag = imageTag == null || imageTag.isBlank() ? null : imageTag.strip();
            environment = environment == null ? List.of() : List.copyOf(environment);
            volumes = volumes == null ? List.of() : List.copyOf(volumes);
            dockerArgs = dockerArgs == null || dockerArgs.isBlank() ? null : dockerArgs.strip();
            port = port == null || port <= 0 ? null : port;
            script = script == null || script.isBlank() ? null : script.strip();
        }

        public ServiceSettings(String path, RunMode runMode, String image, String runtimeProfile, int startTimeoutSeconds,
                               List<String> jvmArgs, String imageTag, List<EnvVar> environment, List<VolumeMount> volumes,
                               String dockerArgs, Boolean healthCheck) {
            this(path, runMode, image, runtimeProfile, startTimeoutSeconds, jvmArgs, imageTag, environment, volumes, dockerArgs,
                    healthCheck, null, null);
        }

        /** Settings from before volumes, docker options and the health check switch existed. */
        public ServiceSettings(String path, RunMode runMode, String image, String runtimeProfile,
                               int startTimeoutSeconds, List<String> jvmArgs, String imageTag, List<EnvVar> environment) {
            this(path, runMode, image, runtimeProfile, startTimeoutSeconds, jvmArgs, imageTag, environment, null, null, null, null, null);
        }

        public static ServiceSettings defaults(String path) {
            return new ServiceSettings(path, RunMode.IMAGE, null, "dev", 120, List.of(), null, List.of(), List.of(), null, null, null, null);
        }

        /** True unless the health check is switched off for this service. */
        public boolean checksHealth() {
            return healthCheck == null || healthCheck;
        }

        /** Whether to ask the readiness check: the developer's (or team's) choice, else whether the project has an endpoint. */
        public boolean checksHealth(boolean projectHasEndpoint) {
            return healthCheck == null ? projectHasEndpoint : healthCheck;
        }

        public ServiceSettings withRunMode(RunMode mode) {
            return new ServiceSettings(path, mode, image, runtimeProfile, startTimeoutSeconds, jvmArgs, imageTag, environment, volumes, dockerArgs, healthCheck, port, script);
        }

        public ServiceSettings withPath(String newPath) {
            return new ServiceSettings(newPath, runMode, image, runtimeProfile, startTimeoutSeconds, jvmArgs, imageTag, environment, volumes, dockerArgs, healthCheck, port, script);
        }

        public ServiceSettings withEnvironment(List<EnvVar> vars) {
            return new ServiceSettings(path, runMode, image, runtimeProfile, startTimeoutSeconds, jvmArgs, imageTag, vars, volumes, dockerArgs, healthCheck, port, script);
        }

        public ServiceSettings withImage(String newImage) {
            return new ServiceSettings(path, runMode, newImage, runtimeProfile, startTimeoutSeconds, jvmArgs, imageTag, environment, volumes, dockerArgs, healthCheck, port, script);
        }

        public ServiceSettings withImageTag(String tag) {
            return new ServiceSettings(path, runMode, image, runtimeProfile, startTimeoutSeconds, jvmArgs, tag, environment, volumes, dockerArgs, healthCheck, port, script);
        }

        public ServiceSettings withDocker(List<VolumeMount> newVolumes, String newDockerArgs) {
            return new ServiceSettings(path, runMode, image, runtimeProfile, startTimeoutSeconds, jvmArgs, imageTag, environment, newVolumes, newDockerArgs, healthCheck, port, script);
        }

        public ServiceSettings withPortAndScript(Integer newPort, String newScript) {
            return new ServiceSettings(path, runMode, image, runtimeProfile, startTimeoutSeconds, jvmArgs, imageTag, environment, volumes,
                    dockerArgs, healthCheck, newPort, newScript);
        }

        public ServiceSettings withRuntimeProfile(String profile) {
            return new ServiceSettings(path, runMode, image, profile, startTimeoutSeconds, jvmArgs, imageTag, environment, volumes,
                    dockerArgs, healthCheck, port, script);
        }

        public ServiceSettings withHealthCheck(Boolean check) {
            return new ServiceSettings(path, runMode, image, runtimeProfile, startTimeoutSeconds, jvmArgs, imageTag, environment, volumes, dockerArgs, check, port, script);
        }
    }

    /**
     * A host folder (or file) mounted into a service's container: {@code docker run --mount type=bind,...}.
     *
     * @param hostPath      on this machine; {@code ~} allowed. Must exist when the service starts
     * @param containerPath absolute path inside the container
     * @param readOnly      the container cannot change it
     */
    public record VolumeMount(String hostPath, String containerPath, boolean readOnly) {

        public VolumeMount {
            hostPath = hostPath == null ? "" : hostPath.strip();
            containerPath = containerPath == null ? "" : containerPath.strip();
        }
    }

    /**
     * @param nativeContainerBuild   build native images in a container (no local GraalVM needed)
     * @param nativeMinFreeMemoryMb  a native build does not start below this much free memory
     */
    public record BuildSettings(boolean nativeContainerBuild, int nativeMinFreeMemoryMb, List<String> profiles) {

        public BuildSettings {
            nativeMinFreeMemoryMb = nativeMinFreeMemoryMb <= 0 ? 6144 : nativeMinFreeMemoryMb;
            profiles = profiles == null || profiles.isEmpty() ? List.of("dev", "qa", "prod") : List.copyOf(profiles);
        }

        public static BuildSettings defaults() {
            return new BuildSettings(true, 6144, List.of("dev", "qa", "prod"));
        }
    }

    /**
     * @param embeddedCredentialsAsError a token in a remote URL blocks a group start
     */
    public record DoctorSettings(boolean embeddedCredentialsAsError) {

        public static DoctorSettings defaults() {
            return new DoctorSettings(false);
        }
    }

    /**
     * @param composeDirs  folders with {@code docker-compose-<environment>.yml} files: the databases,
     *                     brokers and other containers the services need. {@code ~} is allowed.
     * @param composeFiles compose files the developer picked one by one (absolute paths), whatever
     *                     their name and wherever they are, e.g. a personal {@code my-kafka-debug.yml}
     */
    /**
     * @param keepImageVersions how many versions of each service image stay on this machine
     *                          ({@link #DEFAULT_KEEP_IMAGE_VERSIONS} by default, at most {@link #MAX_KEEP_IMAGE_VERSIONS})
     */
    public record DockerSettings(List<String> composeDirs, List<String> composeFiles, Integer keepImageVersions) {

        public static final int DEFAULT_KEEP_IMAGE_VERSIONS = 3;
        public static final int MAX_KEEP_IMAGE_VERSIONS = 5;

        public DockerSettings {
            composeDirs = clean(composeDirs);
            composeFiles = clean(composeFiles);
            keepImageVersions = keepImageVersions == null ? DEFAULT_KEEP_IMAGE_VERSIONS
                    : Math.max(1, Math.min(MAX_KEEP_IMAGE_VERSIONS, keepImageVersions));
        }

        public DockerSettings(List<String> composeDirs, List<String> composeFiles) {
            this(composeDirs, composeFiles, null);
        }

        private static List<String> clean(List<String> values) {
            return values == null ? List.of() : values.stream().map(String::strip).filter(d -> !d.isEmpty()).distinct().toList();
        }

        public static DockerSettings defaults() {
            return new DockerSettings(List.of(), List.of(), DEFAULT_KEEP_IMAGE_VERSIONS);
        }

        public DockerSettings withComposeFiles(List<String> files) {
            return new DockerSettings(composeDirs, files, keepImageVersions);
        }
    }

    /**
     * One environment variable for a service kitout starts (all modes).
     *
     * @param value  the value; always null in the file when {@code secret}: secret values live in the keyring
     * @param secret a password or token: kept in the keyring, never shown or sent back by the API
     */
    /**
     * @param use when the variable applies: when the service runs (the default, and all variables from
     *            before builds had any), when it is built, or both
     */
    public record EnvVar(String name, String value, boolean secret, EnvUse use) {

        public EnvVar {
            name = name == null ? "" : name.strip();
            value = secret ? null : (value == null ? "" : value);
            use = use == null ? EnvUse.RUN : use;
        }

        public EnvVar(String name, String value, boolean secret) {
            this(name, value, secret, EnvUse.RUN);
        }
    }

    /** When an environment variable applies. */
    public enum EnvUse {
        /** When kitout starts the service: {@code docker run -e}, or the process of a local build. */
        RUN,
        /** When kitout builds the service: the process environment of {@code ./mvnw} or {@code ./gradlew}. */
        BUILD,
        /** Both. */
        BOTH;

        public boolean forRun() {
            return this != BUILD;
        }

        public boolean forBuild() {
            return this != RUN;
        }

        /** RUN, BUILD or BOTH, in any case; empty means RUN, as before builds had variables. */
        public static EnvUse parse(String text) {
            if (text == null || text.isBlank()) {
                return RUN;
            }
            try {
                return valueOf(text.strip().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("A variable is used for run, build or both, not " + text);
            }
        }
    }

    /** @param environment variables for every service kitout starts; a service's own variable of the same name wins */
    public record RuntimeSettings(List<EnvVar> environment) {

        public RuntimeSettings {
            environment = environment == null ? List.of() : List.copyOf(environment);
        }

        public static RuntimeSettings defaults() {
            return new RuntimeSettings(List.of());
        }
    }

    /**
     * Where the team's {@code kitout-team.json} comes from: a GitLab project (read with the developer's
     * token, so no clone is needed) or, for trying a file out, a local path.
     */
    public record TeamSettings(String host, String project, String path, String ref, String file) {

        public TeamSettings {
            host = blankToNull(host);
            project = blankToNull(project);
            path = blankToNull(path) == null ? DEFAULT_PATH : path.strip();
            ref = blankToNull(ref) == null ? "main" : ref.strip();
            file = blankToNull(file);
        }

        /** The team file's name in its repository, unless the location names another. */
        public static final String DEFAULT_PATH = "kitout-team.json";

        public static TeamSettings none() {
            return new TeamSettings(null, null, null, null, null);
        }

        public boolean configured() {
            return project != null || file != null;
        }

        /** For people: {@code gitlab.example.com/acme/platform/kitout-team: kitout-team.json@main}, or the file path. */
        public String location() {
            return file != null ? file : host + "/" + project + ": " + path + "@" + ref;
        }

        /**
         * Reads what a developer pastes: a project URL ({@code https://gitlab.example.com/acme/platform/kitout-team}),
         * a file URL (GitLab {@code .../-/blob/main/kitout-team.json}, GitHub {@code .../blob/main/kitout-team.json} or
         * {@code raw.githubusercontent.com/...}), {@code host/group/project}, or a local file path.
         */
        public static TeamSettings parse(String location) {
            String l = location == null ? "" : location.strip();
            if (l.isEmpty()) {
                throw new IllegalArgumentException("Give the team repository, e.g. https://gitlab.example.com/acme/platform/kitout-team");
            }
            if (l.startsWith("/") || l.startsWith("~") || l.startsWith("./") || l.startsWith("../")) {
                return new TeamSettings(null, null, null, null, l);
            }
            String rest = l.replaceFirst("[?#].*$", "").replaceFirst("^https?://", "").replaceAll("/+$", "").replaceFirst("\\.git$", "");
            int slash = rest.indexOf('/');
            if (slash < 0 || rest.indexOf('/', slash + 1) < 0) {
                throw new IllegalArgumentException("'" + l + "' is not a repository; write host/group/project");
            }
            String host = rest.substring(0, slash);
            String project = rest.substring(slash + 1);
            String path = null;
            String ref = null;
            if (host.equalsIgnoreCase("raw.githubusercontent.com")) {
                // raw.githubusercontent.com/<owner>/<repo>/<ref>/<path>
                String[] parts = project.split("/", 4);
                if (parts.length < 4) {
                    throw new IllegalArgumentException("'" + l + "' is not a link to a file (owner/repo/branch/path)");
                }
                return new TeamSettings("github.com", parts[0] + "/" + parts[1], parts[3], parts[2], null);
            }
            if (!project.contains("/-/")) {
                // GitHub: <owner>/<repo>/blob/<ref>/<path>; repositories there are always owner/repo
                String[] parts = project.split("/", 5);
                if (parts.length >= 4 && java.util.Set.of("blob", "raw", "tree").contains(parts[2])) {
                    return new TeamSettings(host, parts[0] + "/" + parts[1], parts.length == 5 ? parts[4] : null, parts[3], null);
                }
            }
            int blob = project.indexOf("/-/blob/");
            if (blob < 0) {
                blob = project.indexOf("/-/raw/");
            }
            if (blob >= 0) {
                String tail = project.substring(project.indexOf("/-/") + 3);
                tail = tail.substring(tail.indexOf('/') + 1);              // after blob/ or raw/
                int sep = tail.indexOf('/');
                ref = sep < 0 ? tail : tail.substring(0, sep);
                path = sep < 0 ? null : tail.substring(sep + 1);
                project = project.substring(0, blob);
            }
            return new TeamSettings(host, project, path, ref, null);
        }

        /**
         * A team file in GitLab, named like a team compose file: the repository ({@code acme/infrastructure})
         * and the file's path in it ({@code kitout/kitout-team.json}), plus the GitLab host and the branch.
         *
         * @param path empty: {@link #DEFAULT_PATH}; a leading {@code /} is dropped (GitLab shows paths that way)
         * @param ref  empty: {@code main}
         */
        public static TeamSettings of(String host, String repo, String path, String ref) {
            String h = blankToNull(host);
            if (h == null) {
                throw new IllegalArgumentException("Give the git host, e.g. github.com or gitlab.example.com");
            }
            h = h.replaceFirst("^https?://", "").replaceAll("/+$", "");
            if (h.contains("/")) {
                throw new IllegalArgumentException("The git host is the name only (e.g. github.com), without a path: '" + host.strip() + "'");
            }
            String r = blankToNull(repo);
            if (r == null) {
                throw new IllegalArgumentException("Give the repository with the team file, e.g. acme/platform/kitout-team");
            }
            r = r.replaceAll("^/+|/+$", "").replaceFirst("\\.git$", "");
            if (r.contains("://") || r.contains("/-/") || r.startsWith(h + "/") || r.matches("[^/]+/[^/]+/(blob|tree|raw)/.*")) {
                throw new IllegalArgumentException("The repository is group/project only (e.g. acme/infrastructure), not a link: '" + repo.strip()
                        + "'. The file goes into the path");
            }
            if (!r.contains("/")) {
                throw new IllegalArgumentException("'" + r + "' is not a repository: write group/project, e.g. acme/infrastructure");
            }
            String p = blankToNull(path);
            if (p != null) {
                p = p.replaceFirst("^/+", "");
                if (p.startsWith("-/") || p.contains("/-/") || p.contains("://")) {
                    throw new IllegalArgumentException("The path is the file's path in the repository (e.g. kitout/kitout-team.json), not a link: '"
                            + path.strip() + "'");
                }
                if (java.util.Arrays.asList(p.split("/")).contains("..")) {
                    throw new IllegalArgumentException("The path must stay inside the repository (no ..): '" + path.strip() + "'");
                }
                if (p.isEmpty() || p.endsWith("/")) {
                    throw new IllegalArgumentException("The path names a folder, not the team file: '" + path.strip() + "'");
                }
            }
            return new TeamSettings(h, r, p, ref, null);
        }

        /** A team file on this machine (a checkout, or one kitout wrote). */
        public static TeamSettings ofFile(String file) {
            String f = blankToNull(file);
            if (f == null) {
                throw new IllegalArgumentException("Give the path of the team file");
            }
            return new TeamSettings(null, null, null, null, f);
        }

        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s.strip();
        }
    }
}
