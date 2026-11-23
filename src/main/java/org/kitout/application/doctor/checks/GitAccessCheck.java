package org.kitout.application.doctor.checks;

import org.kitout.application.doctor.DoctorCheck;
import org.kitout.application.doctor.DoctorContext;
import org.kitout.application.git.GitCli;
import org.kitout.application.git.GitEnvironment;
import org.kitout.application.port.CommandRunner.Result;
import org.kitout.domain.doctor.CheckResult;
import org.kitout.domain.identity.Redactor;
import org.kitout.domain.project.Project;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/** End to end: git can reach the git host for one repository of the group, with prompts disabled. */
@ApplicationScoped
public class GitAccessCheck implements DoctorCheck {

    private final GitCli git;
    private final GitEnvironment gitEnv;

    public GitAccessCheck(GitCli git, GitEnvironment gitEnv) {
        this.git = git;
        this.gitEnv = gitEnv;
    }

    @Override
    public String id() {
        return "git-access";
    }

    @Override
    public int order() {
        return 73;
    }

    @Override
    public boolean appliesTo(DoctorContext context) {
        return context.members().stream().anyMatch(m -> m.identity().isPresent());
    }

    @Override
    public List<CheckResult> run(DoctorContext context) {
        Optional<Project> sample = context.members().stream().filter(m -> m.identity().isPresent()).findFirst();
        Project p = sample.orElseThrow();
        Result r = git.lsRemote(p.path(), gitEnv.env(), gitEnv.removed());
        if (r.succeeded()) {
            return List.of(CheckResult.ok(id(), "Git access", "git ls-remote works for " + p.name()));
        }
        String out = r.output();
        String fix = out.contains("terminal prompts disabled") || out.contains("Authentication failed")
                ? "Store your token once: run 'git fetch' in " + p.path() + " and enter the token as the password"
                : out.contains("Host key verification failed")
                ? "Accept the server's SSH host key once: ssh -T git@" + p.identity().get().host()
                : "Check the network and your access to " + p.identity().get().fullPath();
        return List.of(CheckResult.error(id(), "Git access", "git cannot reach " + p.name() + ": "
                + Redactor.redact(r.firstLine()), fix));
    }
}
