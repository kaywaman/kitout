package org.kitout.infrastructure.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kitout.application.port.GitHubApi;
import org.kitout.application.port.GitLabApi;
import org.kitout.application.port.GitLabApi.GitLabException;
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
import java.util.List;
import java.util.Optional;

/**
 * GitHub REST API over the JDK HTTP client. {@code api.github.com} for github.com, {@code https://<host>/api/v3}
 * for GitHub Enterprise Server. The token, when there is one, goes in the {@code Authorization} header only.
 */
@ApplicationScoped
public class HttpGitHubApi implements GitHubApi {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_PAGES = 20;

    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.getDefault())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public HttpGitHubApi(ObjectMapper json) {
        this.json = json;
    }

    static String apiBase(String host) {
        return host.equalsIgnoreCase("github.com") ? "https://api.github.com" : "https://" + host + "/api/v3";
    }

    @Override
    public List<GitLabApi.Project> ownerRepositories(String host, String owner, Optional<String> token) {
        String o = URLEncoder.encode(owner.strip(), StandardCharsets.UTF_8);
        try {
            return list(host, "/orgs/" + o + "/repos?type=all", token);
        } catch (GitLabException e) {
            if (e.status() != 404) {
                throw e;
            }
            return list(host, "/users/" + o + "/repos?type=owner", token);
        }
    }

    private List<GitLabApi.Project> list(String host, String path, Optional<String> token) {
        List<GitLabApi.Project> result = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            HttpResponse<String> response = get(host, path + "&per_page=100&page=" + page, token);
            result.addAll(projects(read(response.body())));
            if (!hasNext(response.headers().firstValue("link").orElse(""))) {
                break;
            }
        }
        return result.stream().filter(p -> !p.archived()).toList();
    }

    /** The repositories in one page of {@code /orgs/:org/repos} or {@code /users/:user/repos}. */
    static List<GitLabApi.Project> projects(JsonNode array) {
        List<GitLabApi.Project> result = new ArrayList<>();
        array.forEach(r -> result.add(new GitLabApi.Project(
                r.path("name").asText(),
                r.path("full_name").asText(),
                r.path("clone_url").asText(),
                r.path("ssh_url").asText(),
                r.path("default_branch").asText(""),
                r.path("archived").asBoolean(false))));
        return result;
    }

    /** GitHub pages with a {@code Link} header: {@code <...&page=2>; rel="next", <...>; rel="last"}. */
    static boolean hasNext(String link) {
        return link != null && link.contains("rel=\"next\"");
    }

    private HttpResponse<String> get(String host, String pathAndQuery, Optional<String> token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(apiBase(host) + pathAndQuery))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET();
        token.filter(t -> !t.isBlank()).ifPresent(t -> request.header("Authorization", "Bearer " + t));
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return switch (response.statusCode()) {
                case 200 -> response;
                case 401 -> throw new GitLabException(401, "GitHub rejected the token (revoked, expired or wrong)");
                case 403 -> throw new GitLabException(403, response.headers().firstValue("x-ratelimit-remaining").orElse("1").equals("0")
                        ? "GitHub's limit for requests without a token is used up: store a token (Settings, Git token) or wait an hour"
                        : "The token may not list these repositories (needs read access to the organization)");
                case 404 -> throw new GitLabException(404, "No organization or user with that name on " + host + " (or not visible to you)");
                default -> throw new GitLabException(response.statusCode(), "GitHub answered HTTP " + response.statusCode());
            };
        } catch (IOException e) {
            throw new GitLabException(0, "Could not reach " + host + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitLabException(0, "interrupted");
        }
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw new GitLabException(0, "GitHub sent an unexpected answer");
        }
    }
}
