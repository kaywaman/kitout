package org.kitout.application.team;

import org.kitout.application.port.CommandRunner;
import org.kitout.application.port.CommandRunner.Command;
import org.kitout.application.port.CommandRunner.Result;
import org.kitout.domain.identity.Redactor;
import org.kitout.domain.team.JavaVendor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what is installed on the developer's machine, one fixed, read-only way per tool. The team
 * file only says what is required; it can never choose a command to run.
 *
 * <p>Each reading comes with a fingerprint of what it depends on (the tool's resolved binary with
 * size and modification time, {@code JAVA_HOME}, {@code /etc/os-release}, ...). {@link RequirementsService}
 * keeps readings while the fingerprint stays the same, so checking on every kitout call stays fast and
 * still notices the moment a tool is upgraded or switched.
 */
@ApplicationScoped
public class MachineProbe {

    /**
     * @param found       the version (or text, for os and arch) the rule is checked against; empty if not found
     * @param detail      for people: where it was found, which vendor
     * @param problem     a reason the check fails regardless of the version (daemon down, wrong vendor, mismatch)
     */
    public record Reading(String found, String detail, String problem) {

        static Reading notFound(String what) {
            return new Reading("", "", what + " was not found");
        }
    }

    private static final Pattern MAVEN = Pattern.compile("Apache Maven (\\S+)");
    private static final Pattern WRAPPER = Pattern.compile("apache-maven/([0-9][^/]*)/");
    private static final Pattern GIT = Pattern.compile("git version (\\S+)");

    private final CommandRunner runner;
    private final Map<String, String> env;

    @Inject
    public MachineProbe(CommandRunner runner) {
        this(runner, System.getenv());
    }

    MachineProbe(CommandRunner runner, Map<String, String> env) {
        this.runner = runner;
        this.env = env;
    }

    /** What a reading depends on; when this string changes, the reading must be taken again. */
    public String fingerprint(String id, List<Path> projects) {
        return switch (id) {
            case "os" -> file(Path.of("/etc/os-release"));
            case "java" -> binary("java") + "|JAVA_HOME=" + env.getOrDefault("JAVA_HOME", "") + "|" + file(javaHomeBinary().orElse(null));
            case "maven" -> binary("mvn") + "|" + String.join("|", projects.stream().map(p -> file(p.resolve(".mvn/wrapper/maven-wrapper.properties"))).toList());
            case "docker", "docker-compose" -> binary("docker") + "|" + file(Path.of("/usr/bin/dockerd")) + "|DOCKER_HOST=" + env.getOrDefault("DOCKER_HOST", "");
            case "node" -> binary("node");
            case "git" -> binary("git");
            default -> "";   // arch and memory are read every time; it is cheap
        };
    }

    public Reading read(String id, Optional<String> distribution, List<String> vendors, List<Path> projects) {
        return switch (id) {
            case "os" -> os(distribution);
            case "arch" -> new Reading(arch(System.getProperty("os.arch", "")), "", null);
            case "java" -> java(vendors);
            case "maven" -> maven(projects);
            case "docker" -> docker();
            case "docker-compose" -> version(Command.of("docker", "compose", "version", "--short"), "Docker Compose", s -> s.strip().replaceFirst("^v", ""));
            case "node" -> version(Command.of("node", "--version"), "Node.js", s -> s.strip());
            case "git" -> version(Command.of("git", "--version"), "git", s -> first(GIT, s));
            case "memory" -> memory();
            default -> new Reading("", "", "kitout cannot check " + id);
        };
    }

    // ---- the readings ----------------------------------------------------------------------

    Reading os(Optional<String> distribution) {
        Map<String, String> release = osRelease(read(Path.of("/etc/os-release")));
        String id = release.getOrDefault("ID", "");
        String pretty = release.getOrDefault("PRETTY_NAME", id + " " + release.getOrDefault("VERSION_ID", ""));
        if (id.isEmpty()) {
            return new Reading("", "", "/etc/os-release could not be read");
        }
        String problem = distribution.filter(d -> !d.equalsIgnoreCase(id)).map(d -> "This machine runs " + pretty + ", not " + d).orElse(null);
        return new Reading(release.getOrDefault("VERSION_ID", ""), pretty, problem);
    }

    static Map<String, String> osRelease(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                values.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip().replaceAll("^\"|\"$", ""));
            }
        }
        return values;
    }

    static String arch(String osArch) {
        return switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "arm64";
            default -> osArch;
        };
    }

    /** {@code java} on PATH and, if set, {@code $JAVA_HOME/bin/java}: Maven uses JAVA_HOME, the shell uses PATH, both must be right. */
    Reading java(List<String> vendors) {
        Result onPath = runner.run(Command.of("java", "-version").withTimeout(Duration.ofSeconds(20)));
        if (onPath.notFound()) {
            return Reading.notFound("java");
        }
        String version = JavaVendor.version(onPath.output());
        String vendor = JavaVendor.detect(onPath.output());
        String detail = vendor + " " + version + " (java on PATH)";
        String problem = null;
        if (!vendors.isEmpty() && vendors.stream().noneMatch(v -> v.equalsIgnoreCase(vendor))) {
            problem = "java on PATH is " + vendor + "; allowed: " + String.join(", ", vendors);
        }
        Optional<Path> home = javaHomeBinary();
        if (home.isPresent()) {
            Result r = runner.run(Command.of(home.get().toString(), "-version").withTimeout(Duration.ofSeconds(20)));
            String homeVersion = JavaVendor.version(r.output());
            if (!r.succeeded()) {
                problem = "JAVA_HOME (" + env.get("JAVA_HOME") + ") does not contain a working Java";
            } else if (!homeVersion.equals(version)) {
                problem = "java on PATH is " + version + " but JAVA_HOME points to " + homeVersion + "; Maven uses JAVA_HOME";
            }
        } else if (env.containsKey("JAVA_HOME")) {
            problem = "JAVA_HOME (" + env.get("JAVA_HOME") + ") does not contain bin/java";
        }
        return new Reading(version, detail, problem);
    }

    /** The system {@code mvn}, and every project's {@code ./mvnw}: a project builds with its wrapper's Maven. */
    Reading maven(List<Path> projects) {
        Result r = runner.run(Command.of("mvn", "-v").withTimeout(Duration.ofSeconds(30)));
        String system = r.succeeded() ? first(MAVEN, r.output()) : "";
        List<String> wrappers = new ArrayList<>();
        for (Path p : projects) {
            String props = read(p.resolve(".mvn/wrapper/maven-wrapper.properties"));
            String v = first(WRAPPER, props);
            if (!v.isEmpty()) {
                wrappers.add(p.getFileName() + "=" + v);
            }
        }
        String detail = (system.isEmpty() ? "no system mvn" : "mvn " + system) + (wrappers.isEmpty() ? "" : "; wrappers: " + String.join(", ", wrappers));
        if (system.isEmpty()) {
            return new Reading("", detail, r.notFound() ? "mvn was not found" : "mvn -v failed: " + Redactor.redact(r.firstLine()));
        }
        return new Reading(system, detail, null);
    }

    /** The project wrappers' Maven versions, by project folder name, for the per-project rule. */
    public Map<String, String> mavenWrappers(List<Path> projects) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Path p : projects) {
            String v = first(WRAPPER, read(p.resolve(".mvn/wrapper/maven-wrapper.properties")));
            if (!v.isEmpty()) {
                result.put(p.getFileName().toString(), v);
            }
        }
        return result;
    }

    /** The Docker Engine (server) version: the daemon is what runs the containers. */
    Reading docker() {
        Result r = runner.run(Command.of("docker", "version", "--format", "{{.Server.Version}}").withTimeout(Duration.ofSeconds(20)));
        if (r.notFound()) {
            return Reading.notFound("docker");
        }
        String v = r.output().strip();
        if (!r.succeeded() || v.isEmpty() || v.contains(" ")) {
            String out = r.output().toLowerCase(Locale.ROOT);
            return new Reading("", "", out.contains("permission denied")
                    ? "You may not use Docker: add yourself to the docker group (sudo usermod -aG docker $USER) and log in again"
                    : "The Docker daemon is not running (sudo systemctl start docker)");
        }
        return new Reading(v, "Docker Engine " + v, null);
    }

    Reading memory() {
        Matcher m = Pattern.compile("MemTotal:\\s+(\\d+) kB").matcher(read(Path.of("/proc/meminfo")));
        if (!m.find()) {
            return new Reading("", "", "/proc/meminfo could not be read");
        }
        long mb = Long.parseLong(m.group(1)) / 1024;
        return new Reading(String.valueOf(mb), mb + " MB", null);
    }

    private Reading version(Command command, String name, java.util.function.Function<String, String> extract) {
        Result r = runner.run(command.withTimeout(Duration.ofSeconds(20)));
        if (r.notFound()) {
            return Reading.notFound(name);
        }
        if (!r.succeeded()) {
            return new Reading("", "", name + " did not answer: " + Redactor.redact(r.firstLine()));
        }
        String v = extract.apply(r.output());
        return new Reading(v, name + " " + v, v.isEmpty() ? "Could not read the version of " + name : null);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private Optional<Path> javaHomeBinary() {
        String home = env.get("JAVA_HOME");
        if (home == null || home.isBlank()) {
            return Optional.empty();
        }
        Path java = Path.of(home, "bin", "java");
        return Files.isExecutable(java) ? Optional.of(java) : Optional.empty();
    }

    /** The binary the shell would run, resolved through links, with size and modification time. */
    private String binary(String name) {
        for (String dir : env.getOrDefault("PATH", "").split(":")) {
            if (dir.isEmpty()) {
                continue;
            }
            Path candidate = Path.of(dir, name);
            if (Files.isExecutable(candidate)) {
                return name + "=" + file(candidate);
            }
        }
        return name + "=missing";
    }

    private static String file(Path p) {
        if (p == null) {
            return "none";
        }
        try {
            Path real = p.toRealPath();
            return real + ":" + Files.size(real) + ":" + Files.getLastModifiedTime(real).toMillis();
        } catch (IOException e) {
            return p + ":missing";
        }
    }

    private static String read(Path p) {
        try {
            return Files.exists(p) ? Files.readString(p) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String first(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : "";
    }
}
