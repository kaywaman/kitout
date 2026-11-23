package org.kitout.infrastructure.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kitout.application.port.CommandRunner;
import org.kitout.application.port.CommandRunner.Command;
import org.kitout.application.port.CommandRunner.Result;
import org.kitout.application.port.RegistryCredentials;
import org.kitout.domain.identity.Redactor;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads logins the way Docker does: {@code credHelpers} for the host, else {@code credsStore},
 * else the {@code auths} entry in {@code config.json} ({@code $DOCKER_CONFIG} or {@code ~/.docker}).
 * A credential helper is asked through its documented protocol: {@code docker-credential-<name> get}
 * with the server on stdin, the answer as JSON on stdout.
 */
@ApplicationScoped
public class DockerCredentialStore implements RegistryCredentials {

    /** Helper names come from the developer's config.json; still, only plain names become a command. */
    private static final Pattern HELPER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

    private final ObjectMapper json;
    private final CommandRunner runner;

    public DockerCredentialStore(ObjectMapper json, CommandRunner runner) {
        this.json = json;
        this.runner = runner;
    }

    @Override
    public Optional<Credential> lookup(String registryHost) {
        String host = normalize(registryHost);
        for (Path file : configFiles()) {
            JsonNode config = read(file);
            if (config == null) {
                continue;
            }
            Optional<Credential> found = lookup(config, host);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private Optional<Credential> lookup(JsonNode config, String host) {
        String helper = helperFor(config, host);
        if (!helper.isEmpty() && HELPER.matcher(helper).matches()) {
            // the helper may know the server under another spelling (https://, a trailing path): ask its list first
            List<String> servers = new java.util.ArrayList<>(listFromHelper(helper).stream().filter(k -> normalize(k).equals(host)).toList());
            serverKeys(host).forEach(k -> {
                if (!servers.contains(k)) {
                    servers.add(k);
                }
            });
            for (String server : servers) {
                Optional<Credential> c = fromHelper(helper, server);
                if (c.isPresent()) {
                    return c;
                }
            }
        }
        var auths = config.path("auths").properties();
        for (var entry : auths) {
            if (normalize(entry.getKey()).equals(host)) {
                Optional<Credential> c = decodeAuth(entry.getValue().path("auth").asText(""));
                if (c.isPresent()) {
                    return c;
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public String diagnose(String registryHost) {
        String host = normalize(registryHost);
        List<Path> files = configFiles().stream().filter(Files::exists).toList();
        if (files.isEmpty()) {
            return "No Docker config.json found (looked in " + configFiles() + ")";
        }
        StringBuilder sb = new StringBuilder();
        for (Path file : files) {
            JsonNode config = read(file);
            if (config == null) {
                sb.append(file).append(" is not readable JSON. ");
                continue;
            }
            String helper = helperFor(config, host);
            boolean inAuths = false;
            for (var entry : config.path("auths").properties()) {
                inAuths |= normalize(entry.getKey()).equals(host);
            }
            sb.append(file).append(": ")
                    .append(inAuths ? "an entry for " + host : "no entry for " + host)
                    .append(helper.isEmpty() ? ", no credential helper" : ", credential helper '" + helper + "'");
            if (!helper.isEmpty()) {
                Result r = runner.run(Command.of("docker-credential-" + helper, "get").withStdin(host).withTimeout(Duration.ofSeconds(10)));
                sb.append(r.notFound() ? " (docker-credential-" + helper + " is not installed)"
                        : r.succeeded() ? " (answers)" : " (says: " + Redactor.redact(r.firstLine()) + ")");
            }
            sb.append(". ");
        }
        return sb.toString().strip();
    }

    private static String helperFor(JsonNode config, String host) {
        for (var entry : config.path("credHelpers").properties()) {
            if (normalize(entry.getKey()).equals(host)) {
                return entry.getValue().asText("");
            }
        }
        return config.path("credsStore").asText("");
    }

    /** {@code https://images.example.com/v1/} and {@code images.example.com} name the same registry. */
    static String normalize(String server) {
        String s = server.strip().replaceFirst("^[a-zA-Z]+://", "");
        int slash = s.indexOf('/');
        s = slash < 0 ? s : s.substring(0, slash);
        s = s.toLowerCase(java.util.Locale.ROOT);
        if (s.equals("index.docker.io") || s.equals("registry-1.docker.io")) {
            return "docker.io";
        }
        return s.endsWith(":443") ? s.substring(0, s.length() - 4) : s;
    }

    /** The keys Docker may have used for one registry, for a helper that does not list its entries. */
    static List<String> serverKeys(String host) {
        if (host.equals("docker.io") || host.equals("index.docker.io")) {
            return List.of("https://index.docker.io/v1/", "index.docker.io", "docker.io");
        }
        return List.of(host, "https://" + host, "http://" + host);
    }

    /** {@code auth} is base64 of {@code user:secret}. */
    static Optional<Credential> decodeAuth(String auth) {
        if (auth == null || auth.isBlank()) {
            return Optional.empty();
        }
        try {
            String plain = new String(Base64.getDecoder().decode(auth.strip()), StandardCharsets.UTF_8);
            int colon = plain.indexOf(':');
            return colon <= 0 ? Optional.empty() : Optional.of(new Credential(plain.substring(0, colon), plain.substring(colon + 1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Optional<Credential> fromHelper(String helper, String server) {
        Result r = runner.run(Command.of("docker-credential-" + helper, "get").withStdin(server).withTimeout(Duration.ofSeconds(10)));
        if (!r.succeeded()) {
            return Optional.empty();
        }
        try {
            // stderr is merged into the output: a helper's warnings (gpg, pass, D-Bus) must not hide the JSON
            JsonNode node = json.readTree(jsonPart(r.output()));
            String user = node.path("Username").asText("");
            String secret = node.path("Secret").asText("");
            return user.isEmpty() || secret.isEmpty() ? Optional.empty() : Optional.of(new Credential(user, secret));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private List<String> listFromHelper(String helper) {
        Result r = runner.run(Command.of("docker-credential-" + helper, "list").withTimeout(Duration.ofSeconds(10)));
        if (!r.succeeded()) {
            return List.of();
        }
        try {
            List<String> keys = new java.util.ArrayList<>();
            json.readTree(jsonPart(r.output())).properties().forEach(e -> keys.add(e.getKey()));
            return keys;
        } catch (IOException e) {
            return List.of();
        }
    }

    /** The JSON object inside a helper's output, which may carry warning lines before or after it. */
    static String jsonPart(String output) {
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        return start < 0 || end < start ? "{}" : output.substring(start, end + 1);
    }

    /** Where Docker keeps config.json: {@code $DOCKER_CONFIG}, else {@code ~/.docker}; the snap package uses its own folder. */
    static List<Path> configFiles() {
        String dir = System.getenv("DOCKER_CONFIG");
        Path home = Path.of(System.getProperty("user.home"));
        if (dir != null && !dir.isBlank()) {
            return List.of(Path.of(dir).resolve("config.json"));
        }
        return List.of(home.resolve(".docker/config.json"), home.resolve("snap/docker/current/.docker/config.json"));
    }

    private JsonNode read(Path file) {
        try {
            return Files.exists(file) ? json.readTree(file.toFile()) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
