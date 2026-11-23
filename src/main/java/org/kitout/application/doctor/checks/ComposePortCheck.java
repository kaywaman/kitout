package org.kitout.application.doctor.checks;

import org.kitout.application.docker.ComposeService;
import org.kitout.application.docker.ComposeService.Environment;
import org.kitout.application.doctor.DoctorCheck;
import org.kitout.application.doctor.DoctorContext;
import org.kitout.application.port.ComposeInspector;
import org.kitout.domain.doctor.CheckResult;
import org.kitout.domain.project.Project;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * No compose environment publishes a port that a kitout service needs. The usual cause: a developer
 * copied a microservice into a compose file, which then has to be kept up to date by hand and
 * collides with kitout starting the same service. Services belong on the Services page (as
 * "Registry image" with a chosen tag); compose files are for databases, brokers and tools.
 */
@ApplicationScoped
public class ComposePortCheck implements DoctorCheck {

    private final ComposeService compose;
    private final ComposeInspector inspector;

    public ComposePortCheck(ComposeService compose, ComposeInspector inspector) {
        this.compose = compose;
        this.inspector = inspector;
    }

    @Override
    public String id() {
        return "compose-ports";
    }

    @Override
    public int order() {
        return 85;
    }

    @Override
    public boolean appliesTo(DoctorContext context) {
        return !context.members().isEmpty() && !compose.environments().isEmpty();
    }

    @Override
    public List<CheckResult> run(DoctorContext context) {
        List<CheckResult> results = new ArrayList<>();
        for (Environment env : compose.environments()) {
            if (!env.missing().isEmpty()) {
                continue;   // a team environment whose repository is not cloned yet: nothing to read
            }
            Map<String, List<Integer>> published;
            try {
                published = inspector.publishedPorts(env.file());
            } catch (IllegalStateException e) {
                results.add(CheckResult.info(id(), "Compose " + env.id(), "Could not read " + env.file().getFileName() + ": " + e.getMessage()
                        + ". Run 'docker compose -f " + env.file() + " config' to see the problem."));
                continue;
            }
            results.addAll(conflicts(env, published, context.members()));
        }
        if (results.isEmpty()) {
            results.add(CheckResult.ok(id(), "Compose and service ports", "No compose environment publishes a port of a service").asAdvisory());
        }
        return results;
    }

    /** One warning per service whose port a compose service publishes. */
    List<CheckResult> conflicts(Environment env, Map<String, List<Integer>> published, List<Project> services) {
        List<CheckResult> results = new ArrayList<>();
        for (Project s : services) {
            published.forEach((composeService, ports) -> {
                if (!ports.contains(s.devPort())) {
                    return;
                }
                String file = env.file().getFileName().toString();
                boolean sameService = sameName(composeService, s.name());
                String detail = file + " (environment " + env.id() + ") publishes port " + s.devPort() + " for '" + composeService
                        + "', the port of " + s.name() + ". Both cannot run at the same time"
                        + (sameService ? ", and the copy in the compose file has to be updated by hand." : ".");
                String fix = sameService
                        ? "Remove '" + composeService + "' from " + file + " (or put it behind a compose profile) and let kitout run "
                                + s.name() + " as 'Registry image' with the tag you need"
                        : "Publish another port for '" + composeService + "' (in the file or a personal override), or change the port of " + s.name();
                results.add(CheckResult.warning(id(), "Compose " + env.id() + " and " + s.name(), detail, fix).asAdvisory());
            });
        }
        return results;
    }

    /** "orders", "orders-service", "acme-orders" all name the service orders. */
    static boolean sameName(String composeService, String serviceName) {
        String a = composeService.toLowerCase(Locale.ROOT);
        String b = serviceName.toLowerCase(Locale.ROOT);
        return a.equals(b) || a.startsWith(b + "-") || a.endsWith("-" + b) || a.startsWith(b + "_") || a.endsWith("_" + b);
    }
}
