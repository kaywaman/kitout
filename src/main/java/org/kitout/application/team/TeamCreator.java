package org.kitout.application.team;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.ConfigRepository;
import org.kitout.application.env.EnvironmentService;
import org.kitout.application.git.GitCli;
import org.kitout.application.port.JsonReader;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.domain.docker.ComposeFile;
import org.kitout.domain.identity.RemoteUrlParser;
import org.kitout.domain.identity.RepoIdentity;
import org.kitout.domain.project.Project;
import org.kitout.domain.run.DotEnv;
import org.kitout.domain.team.TeamSetup;
import org.kitout.domain.team.TeamSetupParser;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds a team setup from scratch, for a developer who has no {@code kitout-team.json} yet: typically
 * one compose file with the infrastructure (database, Kafka, tools), maybe no services and no CI/CD
 * publishing images yet. The answers become a real team file on this machine
 * ({@code ~/.config/kitout/kitout-team.local.json}), validated like any other, so the rest of kitout works
 * the same, and it can be exported and committed as the team's file later.
 */
@ApplicationScoped
public class TeamCreator {

    public static final String FILE_NAME = "kitout-team.local.json";
    static final int SEARCH_DEPTH = 4;
    private static final Set<String> SKIP = Set.of("node_modules", "target", "build", ".git", ".idea", ".vscode", "src", "dist", ".cache",
            ".m2", ".npm", ".gradle", "snap", ".local");

    /**
     * What the developer answered. Empty strings and lists mean "not yet".
     *
     * @param composeFiles local compose files; one inside a git checkout is stored by repository and path
     * @param envFile      a {@code .env} to take variables from: plain values go to the team file, passwords and keys
     *                     become secret names (your values go to the keyring)
     * @param recordRequirements write this machine's tools as the team's minimum requirements
     */
    public record Draft(String team, String gitlabHost, String cloneFolder, String registryHost, String imagePath,
                        List<String> composeFiles, String envFile, boolean recordRequirements) {
    }

    /** What kitout found on this machine to prefill the form. */
    public record Suggestions(String gitlabHost, String cloneFolder, List<String> composeFiles, List<String> envFiles,
                              List<Map<String, Object>> requirements, boolean allowed) {
    }

    private final TeamService team;
    private final MachineProbe probe;
    private final JsonReader json;
    private final KitoutPaths paths;
    private final GitCli git;
    private final ConfigRepository config;
    private final EnvironmentService environment;
    private final WorkspaceService workspace;
    private final String kitoutVersion;
    private final boolean required;

    public TeamCreator(TeamService team, MachineProbe probe, JsonReader json, KitoutPaths paths, GitCli git, ConfigRepository config,
                       EnvironmentService environment, WorkspaceService workspace,
                       @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev") String kitoutVersion,
                       @ConfigProperty(name = "kitout.team.required", defaultValue = "false") boolean required) {
        this.team = team;
        this.probe = probe;
        this.json = json;
        this.paths = paths;
        this.git = git;
        this.config = config;
        this.environment = environment;
        this.workspace = workspace;
        this.kitoutVersion = kitoutVersion;
        this.required = required;
    }

    /** False when kitout's build says every developer must follow the team's file ({@code kitout.team.required}). */
    public boolean allowed() {
        return !required;
    }

    public Path file() {
        return paths.configDir().resolve(FILE_NAME);
    }

    /** True when the team file in use is the one this class wrote: kitout may change it (add services). */
    public boolean editable() {
        String location = team.status().location();
        return location != null && !location.isBlank() && paths.expand(location).toAbsolutePath().normalize().equals(file());
    }

    // ---- suggestions ----------------------------------------------------------------------------

    public Suggestions suggestions() {
        List<Path> roots = searchRoots();
        List<Path> checkouts = new ArrayList<>();
        List<Path> composeFiles = new ArrayList<>();
        List<Path> envFiles = new ArrayList<>();
        for (Path root : roots) {
            walk(root, checkouts, composeFiles, envFiles);
        }
        Map<String, Long> hosts = checkouts.stream().limit(50).map(c -> git.originUrl(c).flatMap(RemoteUrlParser::parse))
                .flatMap(Optional::stream).collect(Collectors.groupingBy(RepoIdentity::host, Collectors.counting()));
        String host = hosts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
        return new Suggestions(host, config.load().gitlab().workspaceRoot(),
                composeFiles.stream().distinct().limit(30).map(this::display).toList(),
                envFiles.stream().distinct().limit(30).map(this::display).toList(),
                recordedRequirements(), allowed());
    }

    private List<Path> searchRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        config.load().workspaceRoots().forEach(r -> roots.add(paths.expand(r)));
        roots.add(paths.expand(config.load().gitlab().workspaceRoot()));
        for (String name : List.of("work", "projects", "dev", "src", "git", "code", "workspace", "infra", "infrastructure")) {
            roots.add(paths.home().resolve(name));
        }
        return roots.stream().map(p -> p.toAbsolutePath().normalize()).filter(Files::isDirectory).distinct().toList();
    }

    private void walk(Path root, List<Path> checkouts, List<Path> composeFiles, List<Path> envFiles) {
        try {
            Files.walkFileTree(root, Set.of(), SEARCH_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && SKIP.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (Files.isDirectory(dir.resolve(".git"))) {
                        checkouts.add(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (name.equals(".env") && attrs.size() < 64 * 1024) {
                        envFiles.add(file);
                    } else if (ComposeFile.isYaml(name) && name.toLowerCase(Locale.ROOT).contains("compose") && attrs.size() < 1024 * 1024) {
                        try {
                            if (ComposeFile.looksLikeCompose(Files.readString(file))) {
                                composeFiles.add(file);
                            }
                        } catch (IOException | RuntimeException e) {
                            // unreadable: not offered
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // nothing found there
        }
    }

    private String display(Path p) {
        Path home = paths.home();
        return p.startsWith(home) ? "~/" + home.relativize(p) : p.toString();
    }

    // ---- requirements recorded from this machine ---------------------------------------------------

    /** This machine's tools as minimum requirements: same OS release, same Java major and vendor, these versions or newer. */
    List<Map<String, Object>> recordedRequirements() {
        List<Map<String, Object>> checks = new ArrayList<>();
        Function<String, MachineProbe.Reading> read = id -> probe.read(id, Optional.empty(), List.of(), List.of());
        MachineProbe.Reading os = read.apply("os");
        Map<String, String> release = MachineProbe.osRelease(readQuietly(Path.of("/etc/os-release")));
        if (!os.found().isEmpty() && release.containsKey("ID")) {
            checks.add(check("os", os.detail(), os.found(), Map.of("distribution", release.get("ID"))));
        }
        MachineProbe.Reading arch = read.apply("arch");
        if (!arch.found().isEmpty()) {
            checks.add(check("arch", "CPU architecture", arch.found(), Map.of()));
        }
        MachineProbe.Reading java = read.apply("java");
        if (!java.found().isEmpty()) {
            String major = java.found().split("\\.")[0];
            String vendor = java.detail().split(" ")[0];
            checks.add(check("java", "Java " + major + " (" + vendor + ")", major + ".*", Map.of("vendors", List.of(vendor))));
        }
        MachineProbe.Reading maven = read.apply("maven");
        if (!maven.found().isEmpty()) {
            int major = Integer.parseInt(maven.found().replaceAll("^(\\d+).*", "$1"));
            checks.add(check("maven", "Maven " + major, ">=" + plain(maven.found()) + " <" + (major + 1) + ".0.0", Map.of()));
        }
        for (String id : List.of("docker", "docker-compose", "node", "git")) {
            MachineProbe.Reading r = read.apply(id);
            if (!r.found().isEmpty() && r.problem() == null) {
                checks.add(check(id, RequirementsService.defaultName(id), ">=" + plain(r.found()), Map.of()));
            }
        }
        return checks;
    }

    private static String plain(String version) {
        return version.replaceFirst("^v", "").replaceAll("[^0-9.].*$", "");
    }

    private static Map<String, Object> check(String id, String name, String require, Map<String, Object> extra) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("name", name);
        c.putAll(extra);
        c.put("require", require);
        c.put("fix", RequirementsService.defaultFix(id, require));
        return c;
    }

    private static String readQuietly(Path p) {
        try {
            return Files.exists(p) ? Files.readString(p) : "";
        } catch (IOException e) {
            return "";
        }
    }

    // ---- create ----------------------------------------------------------------------------------

    /** Writes the team file from the answers, follows it, and keeps the secret values from the .env in the keyring. */
    public TeamService.Status create(Draft d) {
        if (!allowed()) {
            throw new IllegalStateException("This kitout is set up to follow your team's kitout-team.json only; ask your lead where it is");
        }
        String name = d.team() == null ? "" : d.team().strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Give the team a name, e.g. acme-shop");
        }
        String host = blank(d.gitlabHost()).replaceFirst("^https?://", "").replaceAll("/+$", "");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("$schema", "./kitout-team.schema.json");
        root.put("schemaVersion", 1);
        root.put("team", name);
        Map<String, Object> gitlab = new LinkedHashMap<>();
        gitlab.put("host", host);
        gitlab.put("cloneProtocol", "https");
        gitlab.put("workspaceRoot", blank(d.cloneFolder()).isEmpty() ? "~/work" : d.cloneFolder().strip());
        root.put("gitlab", gitlab);
        boolean images = !blank(d.registryHost()).isEmpty();
        if (images) {
            root.put("registry", new LinkedHashMap<>(Map.of("host", d.registryHost().strip(),
                    "imagePath", blank(d.imagePath()).isEmpty() ? "{namespace}/{project}" : d.imagePath().strip())));
        }
        Map<String, Object> requirements = new LinkedHashMap<>();
        requirements.put("enforcement", "block");
        requirements.put("checks", d.recordRequirements() ? recordedRequirements() : List.of());
        root.put("requirements", requirements);
        // no CI/CD publishing images yet: services run from local builds
        root.put("serviceDefaults", new LinkedHashMap<>(Map.of("runMode", images ? "IMAGE" : "JVM", "runtimeProfile", "dev",
                "startTimeoutSeconds", 120)));
        root.put("services", new ArrayList<>());

        Map<String, String> envValues = Map.of();
        if (!blank(d.envFile()).isEmpty()) {
            Path env = homeFile(d.envFile(), ".env file");
            try {
                envValues = DotEnv.parse(Files.readString(env));
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read " + d.envFile() + ": " + e.getMessage());
            }
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        List<String> secretNames = new ArrayList<>();
        Map<String, String> secretValues = new LinkedHashMap<>();
        envValues.forEach((k, v) -> {
            if (DotEnv.looksSecret(k) || !org.kitout.domain.identity.Redactor.redact(v).equals(v)) {
                secretNames.add(k);
                if (!v.isEmpty()) {
                    secretValues.put(k, v);
                }
            } else {
                variables.put(k, v);
            }
        });
        root.put("variables", variables);
        root.put("secrets", secretNames);

        List<Map<String, Object>> files = new ArrayList<>();
        List<Map<String, Object>> others = new ArrayList<>();
        for (String c : d.composeFiles() == null ? List.<String>of() : d.composeFiles()) {
            Path file = homeFile(c, "compose file");
            try {
                if (!ComposeFile.looksLikeCompose(Files.readString(file))) {
                    throw new IllegalArgumentException(c + " has no top-level services: it is not a compose file");
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read " + c + ": " + e.getMessage());
            }
            Optional<Map<String, Object>> inRepo = repoRef(file, host);
            if (inRepo.isPresent()) {
                files.add(inRepo.get());
                String repo = (String) inRepo.get().get("repo");
                if (others.stream().noneMatch(o -> o.get("repo").equals(repo))) {
                    others.add(new LinkedHashMap<>(Map.of("repo", repo, "description", "Infrastructure: compose files")));
                }
            } else {
                files.add(new LinkedHashMap<>(Map.of("file", display(file))));
            }
        }
        if (!others.isEmpty()) {
            root.put("repositories", others);
        }
        if (!files.isEmpty()) {
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("name", "dev");
            env.put("files", files);
            env.put("profiles", List.of());
            env.put("autoStart", true);
            env.put("description", "The infrastructure: databases, brokers, tools");
            root.put("compose", new LinkedHashMap<>(Map.of("environments", List.of(env))));
        }
        root.put("groups", new ArrayList<>());

        write(root);
        team.use(file().toString());
        secretValues.forEach((k, v) -> environment.setTeamSecret(EnvironmentService.SHARED, k, v));
        return team.status();
    }

    /**
     * Adds the projects in these folders to the team file kitout wrote (one repository each, all in
     * one group "all"). Only for that file: a team's shared file changes by merge request.
     *
     * @return the repositories added
     */
    public List<String> addServices(List<String> folders) {
        if (!editable()) {
            throw new IllegalStateException("The team file in use is your team's: add services to it by merge request");
        }
        Map<String, Object> root;
        try {
            root = json.readObject(Files.readString(file()));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file() + ": " + e.getMessage());
        }
        @SuppressWarnings("unchecked")
        List<Object> services = new ArrayList<>((List<Object>) root.getOrDefault("services", List.of()));
        Set<String> present = services.stream().map(s -> String.valueOf(((Map<?, ?>) s).get("repo")).toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String host = String.valueOf(((Map<?, ?>) root.getOrDefault("gitlab", Map.of())).get("host"));
        List<String> added = new ArrayList<>();
        for (String folder : folders) {
            Path dir = homeFolder(folder);
            List<Project> found = workspace.projectsIn(dir);
            if (found.isEmpty()) {
                throw new IllegalArgumentException(folder + ": " + workspace.whyNoService(dir).orElse("no project kitout can run found"));
            }
            for (Project p : found) {
                RepoIdentity id = p.identity().orElseThrow(() -> new IllegalArgumentException(
                        p.path() + " has no git origin: add one (git remote add origin <url>), so the team can clone it"));
                if (!host.isBlank() && !"null".equals(host) && !host.equalsIgnoreCase(id.host())) {
                    throw new IllegalArgumentException(p.path() + " comes from " + id.host() + ", not from the team's git host " + host);
                }
                if (present.add(id.fullPath().toLowerCase(Locale.ROOT))) {
                    services.add(new LinkedHashMap<>(Map.of("repo", id.fullPath())));
                    added.add(id.fullPath());
                }
            }
        }
        root.put("services", services);
        List<String> members = services.stream().map(s -> String.valueOf(((Map<?, ?>) s).get("repo"))).toList();
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("name", "all");
        group.put("members", members);
        root.put("groups", List.of(group));
        write(root);
        team.refresh();
        return added;
    }

    /**
     * Adds the git checkouts in these folders to the team file kitout wrote as common libraries
     * ({@code javaLibraries} or {@code uiLibraries}): cloned and pulled with the team's other
     * repositories, never loaded or started as services. Only for that file, like {@link #addServices}.
     *
     * @param kind {@code java-library} or {@code ui-library}
     * @return the repositories added
     */
    public List<String> addLibraries(List<String> folders, String kind) {
        String key = switch (kind == null ? "" : kind) {
            case TeamSetup.Repository.JAVA_LIBRARY -> "javaLibraries";
            case TeamSetup.Repository.UI_LIBRARY -> "uiLibraries";
            default -> throw new IllegalArgumentException("A library is java-library or ui-library, not " + kind);
        };
        if (!editable()) {
            throw new IllegalStateException("The team file in use is your team's: add libraries to it by merge request (\"" + key + "\")");
        }
        Map<String, Object> root;
        try {
            root = json.readObject(Files.readString(file()));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file() + ": " + e.getMessage());
        }
        Set<String> present = new LinkedHashSet<>();
        for (String k : List.of("services", "repositories", "javaLibraries", "uiLibraries")) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) root.getOrDefault(k, List.of());
            list.forEach(e -> present.add(String.valueOf(((Map<?, ?>) e).get("repo")).toLowerCase(Locale.ROOT)));
        }
        @SuppressWarnings("unchecked")
        List<Object> libraries = new ArrayList<>((List<Object>) root.getOrDefault(key, List.of()));
        String host = String.valueOf(((Map<?, ?>) root.getOrDefault("gitlab", Map.of())).get("host"));
        List<String> added = new ArrayList<>();
        for (String folder : folders) {
            Path dir = homeFolder(folder);
            RepoIdentity id = git.originUrl(dir).flatMap(RemoteUrlParser::parse).orElseThrow(() -> new IllegalArgumentException(
                    dir + " is not a git checkout with an origin: add one (git remote add origin <url>), so the team can clone it"));
            if (!host.isBlank() && !"null".equals(host) && !host.equalsIgnoreCase(id.host())) {
                throw new IllegalArgumentException(dir + " comes from " + id.host() + ", not from the team's git host " + host);
            }
            if (!present.add(id.fullPath().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(id.fullPath() + " is already in the team setup (a repository is a service, a library or another repository)");
            }
            libraries.add(new LinkedHashMap<>(Map.of("repo", id.fullPath())));
            added.add(id.fullPath());
        }
        root.put(key, libraries);
        write(root);
        team.refresh();
        return added;
    }

    /** The team file kitout wrote, to commit as the team's {@code kitout-team.json}. */
    public String export() {
        if (!Files.isRegularFile(file())) {
            throw new IllegalStateException("No team setup was created on this machine");
        }
        try {
            return Files.readString(file());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file() + ": " + e.getMessage());
        }
    }

    private void write(Map<String, Object> root) {
        TeamSetupParser.Result r = TeamSetupParser.parse(root, kitoutVersion);
        if (r.setup().isEmpty()) {
            throw new IllegalArgumentException("The team setup is not valid yet: " + String.join("; ", r.problems()));
        }
        try {
            Files.createDirectories(file().getParent());
            if (Files.exists(file())) {
                Files.copy(file(), file().resolveSibling(FILE_NAME + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            Path tmp = file().resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, json.writePretty(root));
            Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file() + ": " + e.getMessage());
        }
    }

    /** A compose file inside a git checkout with an origin: named by repository and path, so it works on every machine. */
    private Optional<Map<String, Object>> repoRef(Path file, String host) {
        for (Path dir = file.getParent(); dir != null && dir.startsWith(paths.home()); dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(".git"))) {
                Path root = dir;
                return git.originUrl(root).flatMap(RemoteUrlParser::parse)
                        .filter(id -> host.isEmpty() || id.host().equalsIgnoreCase(host))
                        .map(id -> {
                            Map<String, Object> ref = new LinkedHashMap<>();
                            ref.put("repo", id.fullPath());
                            ref.put("path", root.relativize(file).toString());
                            return ref;
                        });
            }
        }
        return Optional.empty();
    }

    private Path homeFile(String typed, String what) {
        Path file = paths.expand(typed.strip()).toAbsolutePath().normalize();
        if (!file.startsWith(paths.home())) {
            throw new IllegalArgumentException("The " + what + " must be below your home folder: " + typed);
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("No such " + what + ": " + typed);
        }
        return file;
    }

    private Path homeFolder(String typed) {
        Path dir = paths.expand(typed.strip()).toAbsolutePath().normalize();
        if (!dir.startsWith(paths.home()) || !Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a folder below your home folder: " + typed);
        }
        return dir;
    }

    private static String blank(String s) {
        return s == null ? "" : s.strip();
    }
}
