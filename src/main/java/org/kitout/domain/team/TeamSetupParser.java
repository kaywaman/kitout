package org.kitout.domain.team;

import org.kitout.domain.identity.Redactor;
import org.kitout.domain.team.TeamSetup.Build;
import org.kitout.domain.team.TeamSetup.Check;
import org.kitout.domain.team.TeamSetup.ComposeEnvironment;
import org.kitout.domain.team.TeamSetup.Doctor;
import org.kitout.domain.team.TeamSetup.FileRef;
import org.kitout.domain.team.TeamSetup.GitLab;
import org.kitout.domain.team.TeamSetup.Group;
import org.kitout.domain.team.TeamSetup.Preset;
import org.kitout.domain.team.TeamSetup.Registry;
import org.kitout.domain.team.TeamSetup.Requirements;
import org.kitout.domain.team.TeamSetup.Service;
import org.kitout.domain.team.TeamSetup.ServiceDefaults;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a team file (already parsed into maps and lists) into a {@link TeamSetup}, collecting every
 * problem at once with its place in the file, so the author can fix them in one go.
 *
 * <p>A file is refused when it still has {@code <<REPLACE: ...>>} placeholders, contains anything
 * that looks like a credential, needs a newer kitout, or is inconsistent (a group member that is not
 * a service, an unknown check, a broken version rule). Unknown keys are reported as warnings: often a
 * typo, but never a reason to stop the whole team.
 */
public final class TeamSetupParser {

    public static final String PLACEHOLDER = "<<REPLACE";
    public static final Set<String> CHECK_IDS = Set.of("os", "arch", "java", "maven", "docker", "docker-compose", "node", "git", "memory");
    private static final Set<String> RUN_MODES = Set.of("IMAGE", "JVM", "NATIVE", "IDE");
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)+");
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** @param problems the file cannot be used; @param warnings worth fixing, but the file works */
    public record Result(Optional<TeamSetup> setup, List<String> problems, List<String> warnings) {
    }

    private final List<String> problems = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private TeamSetupParser() {
    }

    public static Result parse(Map<String, Object> root, String kitoutVersion) {
        TeamSetupParser p = new TeamSetupParser();
        p.scan(root, "");
        TeamSetup setup = p.read(root, kitoutVersion);
        return new Result(p.problems.isEmpty() ? Optional.of(setup) : Optional.empty(), List.copyOf(p.problems), List.copyOf(p.warnings));
    }

    /** Placeholders and credentials anywhere in the file, with their path. */
    private void scan(Object node, String path) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                String at = path.isEmpty() ? String.valueOf(k) : path + "." + k;
                if (String.valueOf(k).contains(PLACEHOLDER)) {
                    problems.add(path + " still has the placeholder " + k + " as a key");
                }
                scan(v, at);
            });
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                scan(list.get(i), path + "[" + i + "]");
            }
        } else if (node instanceof String s) {
            if (s.contains(PLACEHOLDER)) {
                problems.add(path + " still has the placeholder " + s);
            } else if (!Redactor.redact(s).equals(s)) {
                problems.add(path + " looks like a credential. Secrets never go into the team file: list their names under \"secrets\"");
            }
        }
    }

    private TeamSetup read(Map<String, Object> root, String kitoutVersion) {
        known(root, "", "$schema", "schemaVersion", "team", "minKitoutVersion", "git", "gitlab", "registry", "requirements", "services",
                "serviceDefaults", "variables", "secrets", "groups", "compose", "presets", "build", "doctor", "repositories",
                "javaLibraries", "uiLibraries", "images");
        int schema = integer(root.get("schemaVersion"), "schemaVersion", 0);
        if (schema != TeamSetup.SCHEMA_VERSION) {
            problems.add("schemaVersion " + schema + " is not supported; this kitout reads schemaVersion " + TeamSetup.SCHEMA_VERSION
                    + (schema > TeamSetup.SCHEMA_VERSION ? ". Update kitout" : ""));
        }
        String minKitout = text(root.get("minKitoutVersion"));
        if (!minKitout.isEmpty() && kitoutVersion != null && !kitoutVersion.equals("dev")
                && VersionRule.compare(VersionRule.numbers(kitoutVersion), VersionRule.numbers(minKitout)) < 0) {
            problems.add("The team file needs kitout " + minKitout + " or newer; this is " + kitoutVersion + ". Update kitout");
        }

        // "git" since kitout works with any git server; "gitlab" is the older name and still read
        String gitKey = root.containsKey("git") ? "git" : "gitlab";
        if (root.containsKey("git") && root.containsKey("gitlab")) {
            problems.add("The team file has both \"git\" and \"gitlab\": keep \"git\" only (\"gitlab\" is its older name)");
        }
        Map<String, Object> gl = map(root.get(gitKey), gitKey);
        known(gl, gitKey, "host", "provider", "cloneProtocol", "workspaceRoot");
        GitLab gitlab = new GitLab(text(gl.get("host")).replaceFirst("^https?://", "").replaceAll("/+$", ""), text(gl.get("cloneProtocol")),
                text(gl.get("workspaceRoot")), text(gl.get("provider")));
        if (!org.kitout.domain.git.GitHost.valid(gitlab.provider())) {
            problems.add(gitKey + ".provider is gitlab, github or git (or left out: guessed from the host), not '" + gitlab.provider() + "'");
        }
        if (gitlab.host().isEmpty()) {
            warnings.add(gitKey + ".host is empty: kitout works with what is on this machine only (no cloning or pulling)");
        }

        Map<String, Object> reg = map(root.get("registry"), "registry");
        known(reg, "registry", "host", "imagePath");
        Registry registry = new Registry(text(reg.get("host")), text(reg.get("imagePath")));

        Requirements requirements = requirements(map(root.get("requirements"), "requirements"));

        Map<String, Object> defs = map(root.get("serviceDefaults"), "serviceDefaults");
        known(defs, "serviceDefaults", "runMode", "runtimeProfile", "startTimeoutSeconds");
        ServiceDefaults serviceDefaults = new ServiceDefaults(runMode(text(defs.get("runMode")), "serviceDefaults.runMode", "IMAGE"),
                text(defs.get("runtimeProfile")).isEmpty() ? "dev" : text(defs.get("runtimeProfile")),
                integer(defs.get("startTimeoutSeconds"), "serviceDefaults.startTimeoutSeconds", 120));

        List<Service> services = new ArrayList<>();
        Set<String> repos = new LinkedHashSet<>();
        List<Object> serviceList = list(root.get("services"), "services");
        for (int i = 0; i < serviceList.size(); i++) {
            String at = "services[" + i + "]";
            Map<String, Object> s = map(serviceList.get(i), at);
            known(s, at, "repo", "runMode", "runtimeProfile", "startTimeoutSeconds", "imagePath", "variables", "secrets", "healthCheck");
            String repo = required(s, at, "repo");
            if (!repo.isEmpty() && !repo.contains(PLACEHOLDER)) {
                if (!REPO.matcher(repo).matches()) {
                    problems.add(at + ".repo '" + repo + "' is not a repository path like acme/shop/orders");
                } else if (!repos.add(repo.toLowerCase())) {
                    problems.add(at + ".repo " + repo + " is listed twice: one repository is one service");
                }
            }
            services.add(new Service(repo, s.containsKey("runMode") ? runMode(text(s.get("runMode")), at + ".runMode", null) : null,
                    s.containsKey("runtimeProfile") ? text(s.get("runtimeProfile")) : null,
                    s.containsKey("startTimeoutSeconds") ? integer(s.get("startTimeoutSeconds"), at + ".startTimeoutSeconds", 120) : null,
                    s.containsKey("imagePath") ? text(s.get("imagePath")) : null,
                    variables(s.get("variables"), at + ".variables"), names(s.get("secrets"), at + ".secrets"),
                    bool(s, "healthCheck", at)));
        }
        if (services.isEmpty()) {
            warnings.add("services is empty: list one entry per microservice repository");
        }

        List<TeamSetup.ImageService> images = images(root, repos);

        List<Group> groups = new ArrayList<>();
        List<Object> groupList = list(root.get("groups"), "groups");
        for (int i = 0; i < groupList.size(); i++) {
            String at = "groups[" + i + "]";
            Map<String, Object> g = map(groupList.get(i), at);
            known(g, at, "name", "members", "bff", "bffs", "sequentialStart", "needs");
            List<String> members = strings(g.get("members"), at + ".members");
            members.stream().filter(m -> !m.contains(PLACEHOLDER) && !repos.contains(m.toLowerCase()))
                    .forEach(m -> problems.add(at + ".members: " + m + " is not in services"));
            // "bffs": a list; "bff": one, as before several were possible. Both may be given.
            List<String> bffs = new ArrayList<>(strings(g.get("bffs"), at + ".bffs"));
            String single = text(g.get("bff"));
            if (!single.isEmpty() && bffs.stream().noneMatch(x -> x.equalsIgnoreCase(single))) {
                bffs.add(0, single);
            }
            for (String bff : bffs) {
                if (!bff.contains(PLACEHOLDER) && members.stream().noneMatch(m -> m.equalsIgnoreCase(bff))) {
                    problems.add(at + ".bffs " + bff + " is not one of the group's members");
                }
            }
            groups.add(new Group(required(g, at, "name"), members, bffs,
                    Boolean.TRUE.equals(g.get("sequentialStart")), strings(g.get("needs"), at + ".needs")));
        }

        List<ComposeEnvironment> compose = new ArrayList<>();
        Map<String, Object> composeRoot = map(root.get("compose"), "compose");
        known(composeRoot, "compose", "environments");
        List<Object> envList = list(composeRoot.get("environments"), "compose.environments");
        Set<String> envNames = new LinkedHashSet<>();
        for (int i = 0; i < envList.size(); i++) {
            String at = "compose.environments[" + i + "]";
            Map<String, Object> e = map(envList.get(i), at);
            known(e, at, "name", "files", "profiles", "description", "autoStart");
            List<FileRef> files = new ArrayList<>();
            List<Object> fileList = list(e.get("files"), at + ".files");
            for (int j = 0; j < fileList.size(); j++) {
                String fat = at + ".files[" + j + "]";
                Map<String, Object> f = map(fileList.get(j), fat);
                known(f, fat, "repo", "path", "file");
                if (f.containsKey("file")) {
                    String file = text(f.get("file"));
                    if (!(file.startsWith("~/") || file.startsWith("/"))) {
                        problems.add(fat + ".file must be a path on this machine, e.g. ~/infra/docker-compose-dev.yml");
                    } else if (file.contains("..")) {
                        problems.add(fat + ".file must not contain ..");
                    }
                    warnings.add(fat + " is a file on one machine; for a team, put it in a repository and name it by repo and path");
                    files.add(new FileRef(null, null, file));
                } else {
                    String path = required(f, fat, "path");
                    if (path.startsWith("/")) {
                        // as GitLab shows it; the path is always inside the repository
                        warnings.add(fat + ".path starts with /: read as relative to the repository root (" + path.replaceFirst("^/+", "") + ")");
                        path = path.replaceFirst("^/+", "");
                    }
                    if (java.util.Arrays.asList(path.split("/")).contains("..")) {
                        problems.add(fat + ".path must stay inside the repository (no ..)");
                    }
                    files.add(new FileRef(required(f, fat, "repo"), path));
                }
            }
            if (files.isEmpty()) {
                problems.add(at + ".files is empty");
            }
            String name = required(e, at, "name");
            envNames.add(name);
            boolean autoStart = !(e.get("autoStart") instanceof Boolean b) || b;
            compose.add(new ComposeEnvironment(name, files, strings(e.get("profiles"), at + ".profiles"), text(e.get("description")), autoStart));
        }
        for (int i = 0; i < groups.size(); i++) {
            for (String need : groups.get(i).needs()) {
                if (!envNames.contains(need)) {
                    problems.add("groups[" + i + "].needs: " + need + " is not a compose environment");
                }
            }
        }

        List<Preset> presets = new ArrayList<>();
        List<Object> presetList = list(root.get("presets"), "presets");
        for (int i = 0; i < presetList.size(); i++) {
            String at = "presets[" + i + "]";
            Map<String, Object> p = map(presetList.get(i), at);
            known(p, at, "name", "group", "compose", "runModes", "otherServices");
            Map<String, String> modes = new LinkedHashMap<>();
            map(p.get("runModes"), at + ".runModes").forEach((k, v) -> modes.put(k, runMode(text(v), at + ".runModes." + k, "IDE")));
            presets.add(new Preset(required(p, at, "name"), text(p.get("group")), strings(p.get("compose"), at + ".compose"), modes,
                    runMode(text(p.get("otherServices")), at + ".otherServices", "IMAGE")));
        }

        Map<String, Object> b = map(root.get("build"), "build");
        List<TeamSetup.Repository> others = new ArrayList<>();
        repositories(root, "repositories", TeamSetup.Repository.REPOSITORY, "acme/platform/infrastructure", repos, others);
        repositories(root, "javaLibraries", TeamSetup.Repository.JAVA_LIBRARY, "acme/common/java-commons", repos, others);
        repositories(root, "uiLibraries", TeamSetup.Repository.UI_LIBRARY, "acme/common/ui-components", repos, others);
        known(b, "build", "profiles", "nativeContainerBuild");
        Build build = new Build(strings(b.get("profiles"), "build.profiles"),
                b.get("nativeContainerBuild") instanceof Boolean v ? v : null);

        Map<String, Object> d = map(root.get("doctor"), "doctor");
        known(d, "doctor", "tokenScopes", "embeddedCredentialsAsErrorFrom");
        Doctor doctor = new Doctor(strings(d.get("tokenScopes"), "doctor.tokenScopes"), date(d.get("embeddedCredentialsAsErrorFrom"), "doctor.embeddedCredentialsAsErrorFrom"));

        return new TeamSetup(schema, text(root.get("team")), minKitout, gitlab, registry, requirements, services, serviceDefaults,
                variables(root.get("variables"), "variables"), names(root.get("secrets"), "secrets"), groups, compose, presets, build, doctor, others, images);
    }

    private static final java.util.regex.Pattern IMAGE_NAME = java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9._-]*$");

    /** {@code images}: services run only from their image. Their names join the repositories, so groups can name them. */
    private List<TeamSetup.ImageService> images(Map<String, Object> root, Set<String> repos) {
        List<TeamSetup.ImageService> result = new ArrayList<>();
        if (!root.containsKey("images")) {
            return result;
        }
        List<Object> list = list(root.get("images"), "images");
        for (int i = 0; i < list.size(); i++) {
            String at = "images[" + i + "]";
            Map<String, Object> m = map(list.get(i), at);
            known(m, at, "name", "image", "port", "healthPath", "description");
            String name = required(m, at, "name");
            String image = required(m, at, "image");
            if (!name.isEmpty() && !name.contains(PLACEHOLDER)) {
                if (!IMAGE_NAME.matcher(name).matches()) {
                    problems.add(at + ".name '" + name + "' must be lower case letters, digits, '.', '-' or '_', e.g. payments");
                } else if (!repos.add(name.toLowerCase())) {
                    problems.add(at + ".name " + name + " is used twice (or is also a repository)");
                }
            }
            int slash = image.lastIndexOf('/');
            if (!image.contains(PLACEHOLDER) && image.substring(slash + 1).contains(":")) {
                problems.add(at + ".image " + image + " names a tag: leave it out, every developer chooses the tag for their task");
            }
            int port = integer(m.get("port"), at + ".port", 0);
            if (port <= 0 || port > 65535) {
                problems.add(at + ".port is required: the port the service answers on, e.g. 8080");
            }
            String health = text(m.get("healthPath"));
            if (!health.isEmpty() && !health.startsWith("/")) {
                problems.add(at + ".healthPath " + health + " must start with /, e.g. /q/health/ready");
            }
            result.add(new TeamSetup.ImageService(name, image, port, health.isEmpty() ? null : health, text(m.get("description"))));
        }
        return result;
    }

    /**
     * Reads one list of repositories that are not services: {@code repositories}, {@code javaLibraries}
     * or {@code uiLibraries}. A repository appears once in the whole file: it is a service, a library or another repository.
     */
    private void repositories(Map<String, Object> root, String key, String kind, String example, Set<String> repos,
                              List<TeamSetup.Repository> into) {
        if (!root.containsKey(key)) {
            return;
        }
        List<Object> repoList = list(root.get(key), key);
        for (int i = 0; i < repoList.size(); i++) {
            String at = key + "[" + i + "]";
            Map<String, Object> r = map(repoList.get(i), at);
            known(r, at, "repo", "description");
            String repo = required(r, at, "repo");
            if (!repo.isEmpty() && !repo.contains(PLACEHOLDER)) {
                if (!REPO.matcher(repo).matches()) {
                    problems.add(at + ".repo '" + repo + "' is not a repository path like " + example);
                } else if (!repos.add(repo.toLowerCase())) {
                    problems.add(at + ".repo " + repo + " is listed twice (or is also a service): a repository is a service, a library or another repository");
                }
            }
            into.add(new TeamSetup.Repository(repo, text(r.get("description")), kind));
        }
    }

    /** An optional true/false; anything else is a problem. */
    private Boolean bool(Map<String, Object> m, String key, String at) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        problems.add(at + "." + key + " must be true or false");
        return null;
    }

    private Requirements requirements(Map<String, Object> r) {
        known(r, "requirements", "enforcement", "checks");
        String enforcement = text(r.get("enforcement")).isEmpty() ? "block" : text(r.get("enforcement"));
        if (!enforcement.equals("block") && !enforcement.equals("warn")) {
            problems.add("requirements.enforcement must be block or warn, not " + enforcement);
        }
        List<Check> checks = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        List<Object> list = list(r.get("checks"), "requirements.checks");
        for (int i = 0; i < list.size(); i++) {
            String at = "requirements.checks[" + i + "]";
            Map<String, Object> c = map(list.get(i), at);
            known(c, at, "id", "name", "require", "distribution", "vendors", "unit", "fix", "enforceFrom");
            String id = required(c, at, "id");
            if (!id.isEmpty() && !CHECK_IDS.contains(id)) {
                problems.add(at + ".id " + id + " is unknown; kitout can check " + String.join(", ", new java.util.TreeSet<>(CHECK_IDS)));
            } else if (!ids.add(id)) {
                problems.add(at + ".id " + id + " is listed twice");
            }
            String require = required(c, at, "require");
            if (!require.isEmpty()) {
                try {
                    VersionRule.parse(require);
                } catch (IllegalArgumentException e) {
                    problems.add(at + ".require: " + e.getMessage());
                }
            }
            String fix = text(c.get("fix"));
            if (fix.isEmpty()) {
                warnings.add(at + " has no fix: developers will not know what to do when it fails");
            }
            String name = text(c.get("name"));
            checks.add(new Check(id, name.isEmpty() ? id : name, require, text(c.get("distribution")), strings(c.get("vendors"), at + ".vendors"),
                    fix, date(c.get("enforceFrom"), at + ".enforceFrom")));
        }
        return new Requirements(enforcement, checks);
    }

    // ---- small readers that record problems instead of throwing -------------------------------

    private void known(Map<String, Object> map, String at, String... keys) {
        Set<String> allowed = Set.of(keys);
        map.keySet().stream().filter(k -> !allowed.contains(k))
                .forEach(k -> warnings.add((at.isEmpty() ? "" : at + ".") + k + " is not a known setting (a typo?); it is ignored"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value, String at) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        problems.add(at + " must be an object");
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value, String at) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> l) {
            return (List<Object>) l;
        }
        problems.add(at + " must be a list");
        return List.of();
    }

    private List<String> strings(Object value, String at) {
        return list(value, at).stream().map(TeamSetupParser::text).filter(s -> !s.isEmpty()).toList();
    }

    private List<String> names(Object value, String at) {
        List<String> names = strings(value, at);
        names.stream().filter(n -> !ENV_NAME.matcher(n).matches())
                .forEach(n -> problems.add(at + ": " + n + " is not a valid environment variable name"));
        return names;
    }

    private Map<String, String> variables(Object value, String at) {
        Map<String, String> result = new LinkedHashMap<>();
        map(value, at).forEach((k, v) -> {
            if (!ENV_NAME.matcher(k).matches()) {
                problems.add(at + ": " + k + " is not a valid environment variable name");
            } else if (k.equals("QUARKUS_PROFILE")) {
                problems.add(at + ": QUARKUS_PROFILE comes from runtimeProfile, not from a variable");
            }
            result.put(k, text(v));
        });
        return result;
    }

    private String required(Map<String, Object> map, String at, String key) {
        String v = text(map.get(key));
        if (v.isEmpty()) {
            problems.add(at + (at.isEmpty() ? "" : ".") + key + " is missing");
        }
        return v;
    }

    private int integer(Object value, String at, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        problems.add(at + " must be a number");
        return fallback;
    }

    private String runMode(String value, String at, String fallback) {
        if (value.isEmpty()) {
            return fallback;
        }
        String upper = value.toUpperCase();
        if (!RUN_MODES.contains(upper)) {
            problems.add(at + " must be IMAGE, JVM, NATIVE or IDE, not " + value);
            return fallback;
        }
        return upper;
    }

    private Optional<LocalDate> date(Object value, String at) {
        String s = text(value);
        if (s.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(s));
        } catch (DateTimeParseException e) {
            problems.add(at + " must be a date like 2026-12-01");
            return Optional.empty();
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }
}
