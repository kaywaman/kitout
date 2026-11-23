package org.kitout.application.doctor.checks;

import org.kitout.application.doctor.DoctorCheck;
import org.kitout.application.doctor.DoctorContext;
import org.kitout.application.git.GitCli;
import org.kitout.application.port.CommandRunner.Result;
import org.kitout.domain.doctor.CheckResult;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** git installed, with a name and email for commits. */
@ApplicationScoped
public class GitCheck implements DoctorCheck {

    private final GitCli git;

    public GitCheck(GitCli git) {
        this.git = git;
    }

    @Override
    public String id() {
        return "git";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public List<CheckResult> run(DoctorContext context) {
        Result version = git.version();
        if (!version.succeeded()) {
            return List.of(CheckResult.error(id(), "Git", "git is not installed", "Install git with your package manager"));
        }
        boolean hasName = git.userConfig("user.name").isPresent();
        boolean hasEmail = git.userConfig("user.email").isPresent();
        if (!hasName || !hasEmail) {
            return List.of(CheckResult.warning(id(), "Git identity", version.firstLine() + "; "
                            + (hasName ? "" : "user.name ") + (hasEmail ? "" : "user.email ") + "not set",
                    "git config --global user.name \"Your Name\" && git config --global user.email you@company.com"));
        }
        return List.of(CheckResult.ok(id(), "Git", version.firstLine() + ", identity set"));
    }
}
