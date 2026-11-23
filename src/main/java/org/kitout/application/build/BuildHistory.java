package org.kitout.application.build;

import org.kitout.application.KitoutPaths;
import org.kitout.application.port.JsonReader;
import org.kitout.domain.build.BuildMode;
import org.kitout.domain.build.BuildRequest;
import org.kitout.domain.build.BuildStatus;
import org.kitout.domain.build.NativeBuildErrorExplainer.Explanation;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The finished builds, kept in {@code ~/.local/state/kitout/builds.json} so the list survives a kitout
 * restart, plus the build output the developer removed from the list ("dismissed"), so it is not
 * found again. Written after every change; a damaged file starts an empty list.
 */
@ApplicationScoped
public class BuildHistory {

    private static final Logger LOG = Logger.getLogger(BuildHistory.class);

    public record Stored(List<BuildJob> jobs, Map<String, Long> dismissed) {
    }

    private final JsonReader json;
    private final KitoutPaths paths;

    public BuildHistory(JsonReader json, KitoutPaths paths) {
        this.json = json;
        this.paths = paths;
    }

    Path file() {
        return paths.stateDir().resolve("builds.json");
    }

    public synchronized Stored load() {
        List<BuildJob> jobs = new ArrayList<>();
        Map<String, Long> dismissed = new LinkedHashMap<>();
        if (!Files.isRegularFile(file())) {
            return new Stored(jobs, dismissed);
        }
        try {
            Map<String, Object> root = json.readObject(Files.readString(file()));
            for (Object o : (List<?>) root.getOrDefault("builds", List.of())) {
                Map<?, ?> m = (Map<?, ?>) o;
                Explanation why = m.get("cause") == null ? null : new Explanation(str(m, "cause"), str(m, "fix"));
                jobs.add(BuildJob.restored(str(m, "id"), str(m, "serviceName"),
                        new BuildRequest(str(m, "serviceId"), str(m, "profile"), BuildMode.valueOf(str(m, "mode")), Boolean.TRUE.equals(m.get("skipTests"))),
                        m.get("logFile") == null ? null : Path.of(str(m, "logFile")), (String) m.get("version"), BuildStatus.valueOf(str(m, "status")),
                        instant(m, "createdAt"), instant(m, "startedAt"), instant(m, "finishedAt"), why,
                        Boolean.TRUE.equals(m.get("discovered")), m.get("artifact") == null ? null : Path.of(str(m, "artifact"))));
            }
            Map<?, ?> d = (Map<?, ?>) root.getOrDefault("dismissed", Map.of());
            d.forEach((k, v) -> dismissed.put(String.valueOf(k), ((Number) v).longValue()));
        } catch (IOException | RuntimeException e) {
            LOG.warnf("Ignoring the build history %s: %s", file(), e.getMessage());
        }
        return new Stored(jobs, dismissed);
    }

    public synchronized void save(List<BuildJob> finished, Map<String, Long> dismissed) {
        List<Map<String, Object>> builds = new ArrayList<>();
        for (BuildJob j : finished) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", j.id());
            m.put("serviceId", j.request().serviceId());
            m.put("serviceName", j.serviceName());
            m.put("profile", j.request().profile());
            m.put("mode", j.request().mode().name());
            m.put("skipTests", j.request().skipTests());
            m.put("status", j.status().name());
            m.put("version", j.version().orElse(null));
            m.put("createdAt", j.createdAt().toString());
            m.put("startedAt", j.startedAt().map(Instant::toString).orElse(null));
            m.put("finishedAt", j.finishedAt().map(Instant::toString).orElse(null));
            j.explanation().ifPresent(e -> {
                m.put("cause", e.cause());
                m.put("fix", e.fix());
            });
            m.put("logFile", j.logFile() == null ? null : j.logFile().toString());
            m.put("discovered", j.discovered());
            m.put("artifact", j.artifact().map(Path::toString).orElse(null));
            builds.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("builds", builds);
        root.put("dismissed", dismissed);
        try {
            Files.createDirectories(file().getParent());
            Path tmp = file().resolveSibling("builds.json.tmp");
            Files.writeString(tmp, json.writePretty(root));
            Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            LOG.debugf("Could not keep the build history: %s", e.getMessage());
        }
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Instant instant(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? null : Instant.parse(String.valueOf(v));
    }
}
