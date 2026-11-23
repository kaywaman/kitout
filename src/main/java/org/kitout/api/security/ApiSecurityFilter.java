package org.kitout.api.security;

import org.kitout.api.dto.ErrorView;
import io.vertx.core.http.HttpServerRequest;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.util.Optional;
import java.util.Set;

/**
 * Guards every {@code /api} request. kitout starts processes and changes git remotes, so only the
 * developer's own browser tab or terminal may call it.
 *
 * <ol>
 *   <li>Host header must be a loopback name: blocks DNS-rebinding pages</li>
 *   <li>Origin, when present, must be the app's own address: no CORS needed or allowed</li>
 *   <li>The session token must match</li>
 * </ol>
 * The server itself only listens on 127.0.0.1 ({@code quarkus.http.host}).
 *
 * <p>The filter returns the rejection instead of calling {@code abortWith}: a non-empty result
 * ends the request with that response, an empty one lets it continue. Path and headers come
 * straight from the Vert.x request, which is available before resource matching.
 */
public class ApiSecurityFilter {

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]");
    private final SessionToken token;

    public ApiSecurityFilter(SessionToken token) {
        this.token = token;
    }

    @ServerRequestFilter(preMatching = true)
    public Optional<Response> filter(HttpServerRequest request) {
        String path = request.path();
        if (path == null || !path.startsWith("/api")) {
            return Optional.empty();
        }
        String host = request.getHeader("Host");
        if (host == null || !LOOPBACK.contains(hostName(host))) {
            return reject("forbidden-host", "Requests must be addressed to 127.0.0.1 or localhost");
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.equals("http://" + host)) {
            return reject("forbidden-origin", "Cross-origin requests are not allowed");
        }
        if (!token.matches(request.getHeader(SessionToken.HEADER))) {
            return reject("invalid-token", "Missing or invalid session token. Open kitout with the link printed by 'kitout serve'.");
        }
        return Optional.empty();
    }

    static String hostName(String hostHeader) {
        if (hostHeader.startsWith("[")) {
            int end = hostHeader.indexOf(']');
            return end > 0 ? hostHeader.substring(0, end + 1) : hostHeader;
        }
        int colon = hostHeader.indexOf(':');
        return (colon >= 0 ? hostHeader.substring(0, colon) : hostHeader).toLowerCase();
    }

    private static Optional<Response> reject(String code, String message) {
        return Optional.of(Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new ErrorView(code, message))
                .build());
    }
}
