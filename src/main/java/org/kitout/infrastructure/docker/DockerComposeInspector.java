package org.kitout.infrastructure.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kitout.application.port.CommandRunner;
import org.kitout.application.port.CommandRunner.Command;
import org.kitout.application.port.CommandRunner.Result;
import org.kitout.application.port.ComposeInspector;
import org.kitout.domain.identity.Redactor;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code docker compose -f <file> config --format json}: the file as Compose resolves it (variables,
 * {@code .env}, extends), so kitout sees the same ports Compose would publish. Runs in the file's folder.
 */
@ApplicationScoped
public class DockerComposeInspector implements ComposeInspector {

    private final CommandRunner runner;
    private final ObjectMapper json;

    public DockerComposeInspector(CommandRunner runner, ObjectMapper json) {
        this.runner = runner;
        this.json = json;
    }

    @Override
    public Map<String, List<Integer>> publishedPorts(Path composeFile) {
        Result r = runner.run(Command.of("docker", "compose", "-f", composeFile.toString(), "config", "--format", "json")
                .in(composeFile.getParent()).withTimeout(Duration.ofSeconds(30)));
        if (!r.succeeded()) {
            throw new IllegalStateException(Redactor.redact(r.firstLine()));
        }
        try {
            return ports(json.readTree(r.output()));
        } catch (IOException e) {
            throw new IllegalStateException("docker compose config did not return JSON (Compose 2.17 or newer is needed)");
        }
    }

    /** {@code services.<name>.ports[].published} may be a number, a string, or a range such as "8080-8082". */
    static Map<String, List<Integer>> ports(JsonNode config) {
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        config.path("services").properties().forEach(service -> {
            List<Integer> ports = new ArrayList<>();
            service.getValue().path("ports").forEach(p -> ports.addAll(range(p.path("published").asText(""))));
            if (!ports.isEmpty()) {
                result.put(service.getKey(), ports);
            }
        });
        return result;
    }

    static List<Integer> range(String published) {
        String s = published.strip();
        if (s.isEmpty()) {
            return List.of();
        }
        try {
            int dash = s.indexOf('-');
            if (dash < 0) {
                return List.of(Integer.parseInt(s));
            }
            int from = Integer.parseInt(s.substring(0, dash));
            int to = Integer.parseInt(s.substring(dash + 1));
            List<Integer> all = new ArrayList<>();
            for (int p = from; p <= to && all.size() < 1000; p++) {
                all.add(p);
            }
            return all;
        } catch (NumberFormatException e) {
            return List.of();
        }
    }
}
