package org.kitout.api.security;

import org.kitout.api.dto.ErrorView;
import org.kitout.application.team.RequirementsService;
import io.vertx.core.http.HttpServerRequest;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Stops actions that build, start, clone or pull while the machine does not meet the team's
 * requirements: the same rule as the CLI. Reading, settings, secrets, the doctor, stopping and
 * reset stay possible, so the developer can see what is wrong and fix it. The web UI shows the
 * requirements screen instead of these buttons; this is the backstop.
 */
public class RequirementsFilter {

    static final String CODE = "requirements";

    /** Changes that are always allowed: they help to fix things or only stop things. */
    private static final List<Pattern> ALLOWED = List.of(
            Pattern.compile("/api/(requirements|settings|env|credentials|registry|reset|doctor|kitout|hidden-projects)(/.*)?"),
            Pattern.compile("/api/team(/refresh|/create|/secrets/[^/]+)?"),   // following, reading, secrets; cloning waits
            Pattern.compile("/api/setup/check"),
            Pattern.compile("/api/services/[^/]+/(env|settings|stop|image-tag)(/.*)?"),
            Pattern.compile("/api/services/[^/]+"),          // removing a service from kitout's list
            Pattern.compile("/api/groups/[^/]+/stop"),
            Pattern.compile("/api/groups(/[^/]+)?"),        // editing groups
            Pattern.compile("/api/docker/environments/[^/]+/down"),
            Pattern.compile("/api/docker/(files|browse)(/.*)?"),
            Pattern.compile("/api/builds/[^/]+"),           // cancelling a build, removing attempts
            Pattern.compile("/api/builds/current/remove"),  // removing build output
            Pattern.compile("/api/git/(check|remote-credentials|clean-remotes)"),
            Pattern.compile("/api/services/run-mode"));

    private final RequirementsService requirements;

    public RequirementsFilter(RequirementsService requirements) {
        this.requirements = requirements;
    }

    @ServerRequestFilter(preMatching = true, priority = Priorities.USER + 10)
    public Optional<Response> filter(HttpServerRequest request) {
        String path = request.path();
        if (path == null || !path.startsWith("/api") || isRead(request.method().name()) || allowed(path)) {
            return Optional.empty();
        }
        RequirementsService.Report report;
        try {
            report = requirements.check();
        } catch (RuntimeException e) {
            return Optional.empty();   // a failing check must not lock the developer out
        }
        if (!report.blocked()) {
            return Optional.empty();
        }
        String names = String.join(", ", report.failures().stream().map(RequirementsService.Result::name).toList());
        return Optional.of(Response.status(Response.Status.PRECONDITION_FAILED)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new ErrorView(CODE, "This machine does not meet the team's requirements (" + names
                        + "). Fix them first: see the requirements screen or run 'kitout check'."))
                .build());
    }

    static boolean isRead(String method) {
        return method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS");
    }

    static boolean allowed(String path) {
        return ALLOWED.stream().anyMatch(p -> p.matcher(path).matches());
    }
}
