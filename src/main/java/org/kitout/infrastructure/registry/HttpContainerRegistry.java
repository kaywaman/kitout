package org.kitout.infrastructure.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kitout.application.port.ContainerRegistry;
import org.kitout.application.port.RegistryCredentials;
import org.kitout.application.port.RegistryCredentials.Credential;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tag lists over the Docker Registry HTTP API v2, with the standard token handshake: the registry
 * answers 401 with a {@code WWW-Authenticate: Bearer realm=...} challenge, kitout asks that realm for
 * a pull token with the developer's registry login (Basic auth over HTTPS), and repeats the request.
 * The login comes from {@link RegistryCredentials}; it is sent only to the registry and its realm.
 */
@ApplicationScoped
public class HttpContainerRegistry implements ContainerRegistry {

    private static final int MAX_PAGES = 20;
    private static final Pattern PARAM = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    private static final Pattern NEXT = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"?next\"?");

    private final ObjectMapper json;
    private final RegistryCredentials credentials;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.getDefault())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public HttpContainerRegistry(ObjectMapper json, RegistryCredentials credentials) {
        this.json = json;
        this.credentials = credentials;
    }

    @Override
    public List<String> tags(String registryHost, String repositoryPath) throws RegistryException {
        String base = baseUrl(registryHost);
        String next = "/v2/" + repositoryPath + "/tags/list?n=1000";
        String authorization = null;
        List<String> tags = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES && next != null; page++) {
            HttpResponse<String> r = get(base + next, authorization);
            if (r.statusCode() == 401 && authorization == null) {
                authorization = authorize(r.headers().firstValue("WWW-Authenticate").orElse(""), registryHost, repositoryPath);
                r = get(base + next, authorization);
            }
            if (r.statusCode() == 401 || r.statusCode() == 403) {
                throw new RegistryException(RegistryException.Kind.UNAUTHORIZED, credentials.lookup(registryHost).isEmpty()
                        ? "Not logged in to " + registryHost + ". Log in under Settings, Container registry."
                        : "The registry " + registryHost + " refused your login for " + repositoryPath
                                + ". Log in again under Settings, Container registry, with a user that may pull it.");
            }
            if (r.statusCode() == 404) {
                throw new RegistryException(RegistryException.Kind.NOT_FOUND, "No image repository " + repositoryPath + " in "
                        + registryHost + ". Check that the project's pipeline publishes images there.");
            }
            if (r.statusCode() / 100 != 2) {
                throw new RegistryException(RegistryException.Kind.OTHER, registryHost + " answered " + r.statusCode());
            }
            try {
                json.readTree(r.body()).path("tags").forEach(t -> tags.add(t.asText()));
            } catch (IOException e) {
                throw new RegistryException(RegistryException.Kind.OTHER, registryHost + " sent an unreadable tag list");
            }
            next = nextLink(r.headers().firstValue("Link").orElse("")).orElse(null);
        }
        return tags;
    }

    private String authorize(String challenge, String host, String path) throws RegistryException {
        Optional<Credential> login = credentials.lookup(host);
        String basic = login.map(c -> "Basic " + Base64.getEncoder().encodeToString(
                (c.username() + ":" + c.secret()).getBytes(StandardCharsets.UTF_8))).orElse(null);
        if (challenge.regionMatches(true, 0, "Basic", 0, 5)) {
            return basic;
        }
        if (!challenge.regionMatches(true, 0, "Bearer", 0, 6)) {
            return null;
        }
        Map<String, String> params = challengeParams(challenge);
        String realm = params.get("realm");
        if (realm == null || !(realm.startsWith("https://") || realm.startsWith("http://localhost") || realm.startsWith("http://127.0.0.1"))) {
            throw new RegistryException(RegistryException.Kind.OTHER, "The registry " + host + " asks for a login at an insecure address; kitout does not send it");
        }
        String scope = params.getOrDefault("scope", "repository:" + path + ":pull");
        String url = realm + (realm.contains("?") ? "&" : "?") + "scope=" + enc(scope)
                + (params.containsKey("service") ? "&service=" + enc(params.get("service")) : "");
        HttpResponse<String> r = get(url, basic);
        if (r.statusCode() == 401 || r.statusCode() == 403) {
            return null;   // the retried request then fails with 401 and the login message
        }
        try {
            JsonNode node = json.readTree(r.body());
            String token = node.hasNonNull("token") ? node.get("token").asText() : node.path("access_token").asText("");
            return token.isEmpty() ? null : "Bearer " + token;
        } catch (IOException e) {
            return null;
        }
    }

    /** {@code Bearer realm="https://h/jwt/auth",service="container_registry",scope="repository:a/b:pull"} to a map. */
    static Map<String, String> challengeParams(String challenge) {
        Map<String, String> params = new LinkedHashMap<>();
        Matcher m = PARAM.matcher(challenge);
        while (m.find()) {
            params.put(m.group(1).toLowerCase(java.util.Locale.ROOT), m.group(2));
        }
        return params;
    }

    /** The path of a {@code Link: </v2/x/tags/list?last=b&n=1000>; rel="next"} header. */
    static Optional<String> nextLink(String header) {
        Matcher m = NEXT.matcher(header);
        if (!m.find()) {
            return Optional.empty();
        }
        String link = m.group(1);
        int v2 = link.indexOf("/v2/");
        return v2 < 0 ? Optional.empty() : Optional.of(link.substring(v2));
    }

    static String baseUrl(String host) {
        return (host.startsWith("localhost") || host.startsWith("127.0.0.1") ? "http://" : "https://") + host;
    }

    private HttpResponse<String> get(String url, String authorization) throws RegistryException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json");
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        try {
            return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new RegistryException(RegistryException.Kind.UNREACHABLE, "The registry is not reachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RegistryException(RegistryException.Kind.UNREACHABLE, "Interrupted");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
