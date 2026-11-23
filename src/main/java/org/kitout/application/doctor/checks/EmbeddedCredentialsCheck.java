package org.kitout.application.doctor.checks;

import org.kitout.application.doctor.DoctorCheck;
import org.kitout.application.doctor.DoctorContext;
import org.kitout.application.git.RemoteCleanupService;
import org.kitout.application.git.RemoteCleanupService.Finding;
import org.kitout.domain.doctor.CheckResult;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * No token inside a remote URL. A warning by default; an error that blocks a
 * group start when {@code doctor.embeddedCredentialsAsError} is on (a team can switch it on once everyone cleaned up).
 * Offers the guided cleanup as a one-click action.
 */
@ApplicationScoped
public class EmbeddedCredentialsCheck implements DoctorCheck {

    public static final String ACTION = "clean-remotes";
    private final RemoteCleanupService cleanup;

    public EmbeddedCredentialsCheck(RemoteCleanupService cleanup) {
        this.cleanup = cleanup;
    }

    @Override
    public String id() {
        return "embedded-credentials";
    }

    @Override
    public int order() {
        return 80;
    }

    @Override
    public boolean appliesTo(DoctorContext context) {
        return !context.members().isEmpty();
    }

    @Override
    public List<CheckResult> run(DoctorContext context) {
        List<Finding> findings = cleanup.find(context.members());
        if (findings.isEmpty()) {
            return List.of(CheckResult.ok(id(), "Credentials in remote URLs", "No tokens in remote URLs"));
        }
        long repos = findings.stream().map(Finding::serviceId).distinct().count();
        String detail = repos + " repositories keep a token in a remote URL (in plain text in .git/config)";
        String fix = "Use 'Clean remotes' here, or follow the credential guide; then revoke the old token on the git server";
        CheckResult result = context.config().doctor().embeddedCredentialsAsError()
                ? CheckResult.error(id(), "Credentials in remote URLs", detail, fix)
                : CheckResult.warning(id(), "Credentials in remote URLs", detail, fix);
        return List.of(result.withAction(ACTION).withItems(findings.stream().map(Finding::describe).toList()));
    }
}
