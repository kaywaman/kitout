package org.kitout.application.doctor;

import org.kitout.application.config.ConfigRepository;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.workspace.GroupService;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.domain.doctor.CheckResult;
import org.kitout.domain.doctor.Severity;
import org.kitout.domain.group.Group;
import org.kitout.domain.project.Project;
import io.quarkus.arc.All;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs every {@link DoctorCheck} in parallel and collects the results in a stable order.
 * A check that throws or times out becomes a WARNING result, so one broken check never hides
 * the others.
 */
@ApplicationScoped
public class DoctorService {

    private static final Logger LOG = Logger.getLogger(DoctorService.class);
    static final Duration CHECK_TIMEOUT = Duration.ofSeconds(40);

    public record Report(List<CheckResult> results) {

        public boolean hasErrors() {
            return results.stream().anyMatch(r -> r.severity().blocksStart());
        }

        public long count(Severity severity) {
            return results.stream().filter(r -> r.severity() == severity).count();
        }
    }

    private final List<DoctorCheck> checks;
    private final ConfigRepository config;
    private final WorkspaceService workspace;
    private final GroupService groups;

    public DoctorService(@All List<DoctorCheck> checks, ConfigRepository config, WorkspaceService workspace, GroupService groups) {
        this.checks = checks.stream().sorted(Comparator.comparingInt(DoctorCheck::order).thenComparing(DoctorCheck::id)).toList();
        this.config = config;
        this.workspace = workspace;
        this.groups = groups;
    }

    public Report run(Optional<String> groupName) {
        KitoutConfig cfg = config.load();
        List<Project> all = workspace.projects();
        Optional<Group> group = groupName.filter(n -> !n.isBlank()).map(groups::require);
        List<Project> members = group
                .map(g -> all.stream().filter(p -> g.members().contains(p.id())).toList())
                .orElse(all);
        return run(new DoctorContext(cfg, group, members));
    }

    Report run(DoctorContext context) {
        List<DoctorCheck> applicable = checks.stream().filter(c -> c.appliesTo(context)).toList();
        List<CheckResult> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<CheckResult>>> futures = applicable.stream()
                    .map(c -> pool.submit(() -> c.run(context)))
                    .toList();
            for (int i = 0; i < applicable.size(); i++) {
                results.addAll(collect(applicable.get(i), futures.get(i)));
            }
        }
        return new Report(results);
    }

    private static List<CheckResult> collect(DoctorCheck check, Future<List<CheckResult>> future) {
        try {
            return future.get(CHECK_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return List.of(CheckResult.warning(check.id(), check.id(), "The check did not finish within "
                    + CHECK_TIMEOUT.toSeconds() + " s", "Run the doctor again; if it keeps timing out, check your network"));
        } catch (Exception e) {
            LOG.debugf(e, "Doctor check %s failed", check.id());
            return List.of(CheckResult.warning(check.id(), check.id(), "The check itself failed: " + e.getMessage(),
                    "Report this to the kitout maintainers"));
        }
    }
}
