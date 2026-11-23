package org.kitout.infrastructure.network;

import org.kitout.application.port.CommandRunner;
import org.kitout.application.port.CommandRunner.Command;
import org.kitout.application.port.CommandRunner.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kitout.application.port.NetworkProbe;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TCP connects, the Quarkus readiness endpoint, and {@code ss} to name the process on a port.
 */
@ApplicationScoped
public class DefaultNetworkProbe implements NetworkProbe {

    private static final Pattern SS_USER = Pattern.compile("\\(\"([^\"]+)\",pid=(\\d+)");
    private final CommandRunner runner;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(800))
            .proxy(HttpClient.Builder.NO_PROXY)
            .build();

    public DefaultNetworkProbe(CommandRunner runner, ObjectMapper json) {
        this.runner = runner;
        this.json = json;
    }

    @Override
    public boolean canConnect(String host, int port, Duration timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 200 ready; 404 means the service has no health extension; anything else (usually 503) is
     * not ready, with the names of the DOWN checks from the SmallRye Health JSON body.
     */
    @Override
    public HealthCheck health(int port, String path) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(2)).GET().build();
        } catch (IllegalArgumentException e) {
            return HealthCheck.of(Health.UNREACHABLE);
        }
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return switch (response.statusCode()) {
                case 200 -> HealthCheck.of(Health.READY);
                case 404 -> HealthCheck.of(Health.NO_HEALTH_ENDPOINT);
                default -> new HealthCheck(Health.NOT_READY, downChecks(response.body()));
            };
        } catch (IOException e) {
            return HealthCheck.of(Health.UNREACHABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HealthCheck.of(Health.UNREACHABLE);
        }
    }

    /** {"status":"DOWN","checks":[{"name":"...","status":"DOWN"}]} -> names of DOWN checks. */
    List<String> downChecks(String body) {
        List<String> names = new ArrayList<>();
        try {
            JsonNode root = json.readTree(body == null ? "" : body);
            if (root != null) {
                root.path("checks").forEach(c -> {
                    if (!"UP".equalsIgnoreCase(c.path("status").asText())) {
                        names.add(c.path("name").asText("unnamed check"));
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            // not a SmallRye Health answer: no details
        }
        return names;
    }

    @Override
    public Optional<String> portHolder(int port) {
        Result r = runner.run(Command.of("ss", "-ltnpH", "sport = :" + port));
        if (!r.succeeded()) {
            return Optional.empty();
        }
        Matcher m = SS_USER.matcher(r.output());
        if (m.find()) {
            return Optional.of(m.group(1) + " (pid " + m.group(2) + ")");
        }
        return r.output().isBlank() ? Optional.empty() : Optional.of("a process of another user or a container");
    }
}
