package org.kitout.application.workspace;

import org.kitout.application.git.GitCli;
import org.kitout.domain.config.ConfigFileParser;
import org.kitout.domain.config.EndpointExtractor;
import org.kitout.domain.config.HealthEndpoint;
import org.kitout.domain.config.QuarkusConfig;
import org.kitout.domain.identity.RemoteUrlParser;
import org.kitout.domain.identity.RepoIdentity;
import org.kitout.domain.project.BuildTool;
import org.kitout.domain.project.Project;
import org.kitout.domain.project.ProjectType;
import org.kitout.domain.config.NodePackage;
import org.kitout.domain.config.SpringBootConfig;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one directory into a {@link Project} of its type, or nothing if kitout cannot run it.
 * Information Expert for "what is this repository": build file, config files, git origin.
 */
@ApplicationScoped
public class ProjectReader {

    private static final Pattern MAVEN_RELEASE =
            Pattern.compile("<maven\\.compiler\\.(?:release|target)>\\s*(\\d+)\\s*<");
    private static final Pattern GRADLE_TOOLCHAIN =
            Pattern.compile("languageVersion(?:\\.set\\(|\\s*=\\s*)\\s*JavaLanguageVersion\\.of\\((\\d+)\\)");
    private static final String RESOURCES = "src/main/resources";

    private final GitCli git;

    public ProjectReader(GitCli git) {
        this.git = git;
    }

    /**
     * The project in this folder, if kitout can run it: Quarkus or Spring Boot (by their dependencies),
     * a plain Java application (its build declares a main class, so libraries are left out), or a Node
     * project with a start script ({@code dev}, {@code start} or {@code serve}).
     */
    public Optional<Project> read(Path dir) {
        Optional<BuildTool> tool = detectBuildTool(dir);
        if (tool.isEmpty()) {
            return Optional.empty();
        }
        if (tool.get() == BuildTool.NPM) {
            return readNode(dir);
        }
        String buildFile = readBuildFiles(dir, tool.get());
        if (isKitoutItself(dir, buildFile)) {
            return Optional.empty();
        }
        ProjectType type = javaType(buildFile).orElse(null);
        if (type == null) {
            return Optional.empty();
        }
        if (type != ProjectType.QUARKUS) {
            return Optional.of(readJava(dir, tool.get(), buildFile, type));
        }
        QuarkusConfig config = readConfig(dir, "dev");
        Optional<RepoIdentity> identity = git.originUrl(dir).flatMap(RemoteUrlParser::parse);
        return Optional.of(new Project(
                Project.idFor(identity, dir),
                identity.map(RepoIdentity::project).orElse(dir.getFileName().toString()),
                dir.toAbsolutePath().normalize(),
                identity,
                tool.get(),
                EndpointExtractor.httpPort(config),
                EndpointExtractor.httpPortIsAdvisory(config),
                javaRelease(buildFile),
                EndpointExtractor.infrastructure(config),
                EndpointExtractor.restClientUrls(config),
                HealthEndpoint.of(config, EndpointExtractor.httpPort(config)),
                version(dir, tool.get())));
    }

    /** Which Java type a build file describes: Quarkus first (it may use Spring compatibility), then Spring Boot, then a main class. */
    static Optional<ProjectType> javaType(String buildFile) {
        if (buildFile.contains("io.quarkus")) {
            return Optional.of(ProjectType.QUARKUS);
        }
        if (buildFile.contains("org.springframework.boot")) {
            return Optional.of(ProjectType.SPRING_BOOT);
        }
        return MAIN_CLASS.matcher(buildFile).find() ? Optional.of(ProjectType.JAVA) : Optional.empty();
    }

    /** A main class in the build: the jar manifest, shade/assembly/exec plugins, or Gradle's application plugin. */
    private static final Pattern MAIN_CLASS = Pattern.compile(
            "<mainClass>|<Main-Class>|<exec\\.mainClass>|\\bmainClass(?:Name)?\\b|id\\s*\\(?\\s*['\"]application['\"]|apply\\s+plugin\\s*:\\s*['\"]application['\"]");

    /** A Spring Boot or plain Java project. Spring Boot reads its port and health check from its config; plain Java has none. */
    private Project readJava(Path dir, BuildTool tool, String buildFile, ProjectType type) {
        Optional<RepoIdentity> identity = git.originUrl(dir).flatMap(RemoteUrlParser::parse);
        String id = Project.idFor(identity, dir);
        String name = identity.map(RepoIdentity::project).orElse(dir.getFileName().toString());
        if (type == ProjectType.SPRING_BOOT) {
            QuarkusConfig config = readSpringConfig(dir, "dev");
            return new Project(id, name, dir.toAbsolutePath().normalize(), identity, tool,
                    SpringBootConfig.port(config), SpringBootConfig.portIsAdvisory(config), javaRelease(buildFile),
                    SpringBootConfig.infrastructure(config), Map.of(), SpringBootConfig.health(config), version(dir, tool),
                    type, List.of(), Optional.empty());
        }
        // a plain Java app: no port or health check unless the developer sets a port (Options)
        return new Project(id, name, dir.toAbsolutePath().normalize(), identity, tool, 0, false, javaRelease(buildFile),
                List.of(), Map.of(), new HealthEndpoint(0, "", false), version(dir, tool), type, List.of(), Optional.empty());
    }

    /** A Node project with a start script; its port is a guess (marked advisory) the developer can correct. */
    private Optional<Project> readNode(Path dir) {
        String pkg = readText(dir.resolve("package.json")).orElse("");
        Map<String, String> scripts = NodePackage.scripts(pkg);
        List<String> start = NodePackage.startScripts(scripts);
        if (start.isEmpty()) {
            return Optional.empty();   // a library or tooling package: nothing to run
        }
        String vite = readText(dir.resolve("vite.config.ts")).or(() -> readText(dir.resolve("vite.config.js")))
                .or(() -> readText(dir.resolve("vite.config.mjs"))).orElse("");
        String angular = readText(dir.resolve("angular.json")).orElse("");
        int port = NodePackage.port(scripts.get(start.get(0)), vite, angular);
        Optional<RepoIdentity> identity = git.originUrl(dir).flatMap(RemoteUrlParser::parse);
        String name = identity.map(RepoIdentity::project).or(() -> NodePackage.name(pkg)).orElse(dir.getFileName().toString());
        Optional<String> version = Optional.ofNullable(org.kitout.domain.config.JsonText.stringField(pkg, "version"));
        return Optional.of(new Project(Project.idFor(identity, dir), name, dir.toAbsolutePath().normalize(), identity, BuildTool.NPM,
                port, true, Optional.empty(), List.of(), Map.of(), new HealthEndpoint(port, "", true), version,
                ProjectType.NODE, start, Optional.empty()));
    }

    /** Spring Boot's files: {@code application.*}, then {@code application-<profile>.*} on top; properties over YAML in each. */
    public QuarkusConfig readSpringConfig(Path dir, String profile) {
        Path resources = dir.resolve(RESOURCES);
        java.util.function.Function<String, List<Map<String, String>>> files = base -> {
            List<Map<String, String>> list = new java.util.ArrayList<>();
            for (String yaml : new String[] {base + ".yaml", base + ".yml"}) {
                readText(resources.resolve(yaml)).ifPresent(t -> list.add(ConfigFileParser.parseYaml(t)));
            }
            readText(resources.resolve(base + ".properties")).ifPresent(t -> list.add(ConfigFileParser.parseProperties(t)));
            return list;
        };
        return new QuarkusConfig(SpringBootConfig.merge(files.apply("application"), files.apply("application-" + profile)), "-");
    }

    /** Cheap check for the folder picker: a project kitout could run here (any type). */
    public boolean looksLikeService(Path dir) {
        return detectBuildTool(dir).map(tool -> {
            if (tool == BuildTool.NPM) {
                return !NodePackage.startScripts(NodePackage.scripts(readText(dir.resolve("package.json")).orElse(""))).isEmpty();
            }
            String buildFile = readBuildFiles(dir, tool);
            return javaType(buildFile).isPresent() && !isKitoutItself(dir, buildFile);
        }).orElse(false);
    }

    /**
     * Why a repository folder holds no Quarkus project kitout can run, for people; empty if it does.
     * Looks at the folder itself (a multi-module project is found by the scan below it).
     */
    public Optional<String> whyNoService(Path dir) {
        Optional<BuildTool> tool = detectBuildTool(dir);
        if (tool.isEmpty()) {
            return Optional.of("no pom.xml, build.gradle or package.json in it, so it is not a service");
        }
        if (tool.get() == BuildTool.NPM) {
            return NodePackage.startScripts(NodePackage.scripts(readText(dir.resolve("package.json")).orElse(""))).isEmpty()
                    ? Optional.of("its package.json has no dev, start or serve script, so there is nothing to run (a library?)")
                    : Optional.empty();
        }
        String buildFile = readBuildFiles(dir, tool.get());
        if (isKitoutItself(dir, buildFile)) {
            return Optional.of("it is kitout itself, which is never a service");
        }
        if (javaType(buildFile).isEmpty()) {
            return Optional.of("its build file uses neither Quarkus nor Spring Boot and names no main class (a library?)");
        }
        return Optional.empty();
    }

    /**
     * kitout's own source is a Quarkus project too, but not a service of the developer's environment:
     * it is never listed, scanned or started. Recognised by its artifact id or its CLI entry point.
     */
    static boolean isKitoutItself(Path dir, String buildFile) {
        return buildFile.contains("<artifactId>kitout</artifactId>")
                || java.nio.file.Files.exists(dir.resolve("src/main/java/org/kitout/cli/KitoutApplication.java"));
    }

    /** The project's own version from its build file; empty if it cannot tell. */
    static Optional<String> version(Path dir, BuildTool tool) {
        if (Files.isRegularFile(dir.resolve("pom.xml"))) {
            return readText(dir.resolve("pom.xml")).flatMap(org.kitout.domain.project.ProjectVersion::fromPom);
        }
        String gradle = readText(dir.resolve("build.gradle")).or(() -> readText(dir.resolve("build.gradle.kts"))).orElse("");
        return org.kitout.domain.project.ProjectVersion.fromGradle(gradle, readText(dir.resolve("gradle.properties")).orElse(""));
    }

    /** All config files merged, properties winning over YAML (as in Quarkus, by ordinal). */
    public QuarkusConfig readConfig(Path dir, String profile) {
        Map<String, String> raw = new HashMap<>();
        Path resources = dir.resolve(RESOURCES);
        for (String yaml : new String[] {"application.yaml", "application.yml"}) {
            readText(resources.resolve(yaml)).ifPresent(t -> raw.putAll(ConfigFileParser.parseYaml(t)));
        }
        readText(resources.resolve("application.properties")).ifPresent(t -> raw.putAll(ConfigFileParser.parseProperties(t)));
        return new QuarkusConfig(raw, profile);
    }

    static Optional<BuildTool> detectBuildTool(Path dir) {
        for (BuildTool tool : BuildTool.values()) {
            for (String file : tool.buildFiles()) {
                if (Files.isRegularFile(dir.resolve(file))) {
                    return Optional.of(tool);
                }
            }
        }
        return Optional.empty();
    }

    private static String readBuildFiles(Path dir, BuildTool tool) {
        StringBuilder sb = new StringBuilder();
        tool.buildFiles().forEach(f -> readText(dir.resolve(f)).ifPresent(sb::append));
        if (tool == BuildTool.GRADLE) {
            readText(dir.resolve("gradle.properties")).ifPresent(sb::append);
        }
        return sb.toString();
    }

    private static Optional<Integer> javaRelease(String buildFile) {
        for (Pattern p : new Pattern[] {MAVEN_RELEASE, GRADLE_TOOLCHAIN}) {
            Matcher m = p.matcher(buildFile);
            if (m.find()) {
                return Optional.of(Integer.parseInt(m.group(1)));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> readText(Path file) {
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readString(file)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
