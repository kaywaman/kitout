package org.kitout.infrastructure.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kitout.application.port.GitLabApi;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * GitLab REST API v4 over the JDK HTTP client: no extra dependency, native-image friendly.
 * The token goes in the {@code PRIVATE-TOKEN} header only, never in a URL.
 */
@ApplicationScoped
public class HttpGitLabApi implements GitLabApi {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_PAGES = 20;

    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.getDefault())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public HttpGitLabApi(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public List<Project> groupProjects(String host, String groupPath, String token) {
        List<Project> projects = new ArrayList<>();
        String group = URLEncoder.encode(groupPath, StandardCharsets.UTF_8);
        for (int page = 1; page <= MAX_PAGES; page++) {
            HttpResponse<String> response = get(host, "/api/v4/groups/" + group + "/projects?include_subgroups=true"
                    + "&archived=false&per_page=100&order_by=path&sort=asc&page=" + page, token);
            JsonNode array = parse(response.body());
            array.forEach(p -> projects.add(new Project(
                    p.path("name").asText(),
                    p.path("path_with_namespace").asText(),
                    p.path("http_url_to_repo").asText(),
                    p.path("ssh_url_to_repo").asText(),
                    p.path("default_branch").asText(""),
                    p.path("archived").asBoolean(false))));
            String next = response.headers().firstValue("x-next-page").orElse("");
            if (next.isBlank()) {
                break;
            }
        }
        return projects.stream().filter(p -> !p.archived()).toList();
    }

    @Override
    public TokenInfo tokenInfo(String host, String token) {
        JsonNode node = parse(get(host, "/api/v4/personal_access_tokens/self", token).body());
        List<String> scopes = new ArrayList<>();
        node.path("scopes").forEach(s -> scopes.add(s.asText()));
        String expires = node.path("expires_at").asText("");
        return new TokenInfo(node.path("name").asText(), node.path("active").asBoolean(false) && !node.path("revoked").asBoolean(false),
                scopes, expires.isBlank() || expires.equals("null") ? Optional.empty() : Optional.of(LocalDate.parse(expires)));
    }

    @Override
    public FileContent file(String host, String projectPath, String filePath, String ref, String token) {
        String project = URLEncoder.encode(projectPath, StandardCharsets.UTF_8);
        String file = URLEncoder.encode(filePath, StandardCharsets.UTF_8);
        JsonNode node = parse(get(host, "/api/v4/projects/" + project + "/repository/files/" + file
                + "?ref=" + URLEncoder.encode(ref, StandardCharsets.UTF_8), token).body());
        String content = node.path("content").asText("");
        String text = "base64".equals(node.path("encoding").asText("base64"))
                ? new String(java.util.Base64.getMimeDecoder().decode(content), StandardCharsets.UTF_8)
                : content;
        return new FileContent(text, node.path("last_commit_id").asText(""));
    }

    @Override
    public void ping(String host) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://" + host + "/api/v4/version"))
                .timeout(TIMEOUT).GET().build();
        try {
            http.send(request, HttpResponse.BodyHandlers.discarding());   // any HTTP answer means TLS and network work
        } catch (IOException e) {
            throw new GitLabException(0, describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitLabException(0, "interrupted");
        }
    }

    private HttpResponse<String> get(String host, String pathAndQuery, String token) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://" + host + pathAndQuery))
                .timeout(TIMEOUT)
                .header("PRIVATE-TOKEN", token)
                .header("Accept", "application/json")
                .GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            switch (response.statusCode()) {
                case 200 -> {
                    return response;
                }
                case 401 -> throw new GitLabException(401, "GitLab rejected the token (revoked, expired or wrong)");
                case 403 -> throw new GitLabException(403, "The token lacks a scope for this request (needs read_api)");
                case 404 -> throw new GitLabException(404, "Not found on GitLab (check the group path and your access)");
                default -> throw new GitLabException(response.statusCode(), "GitLab answered HTTP " + response.statusCode());
            }
        } catch (IOException e) {
            throw new GitLabException(0, describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitLabException(0, "interrupted");
        }
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw new GitLabException(0, "GitLab sent an unexpected answer");
        }
    }

    private static String describe(IOException e) {
        String type = e.getClass().getSimpleName();
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (e instanceof javax.net.ssl.SSLException || message.contains("PKIX") || message.contains("certificate")) {
            return "TLS/certificate problem: " + message;
        }
        return type + (message.isEmpty() ? "" : ": " + message);
    }
}
