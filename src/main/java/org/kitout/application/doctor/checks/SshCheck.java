package org.kitout.application.doctor.checks;

import org.kitout.application.doctor.DoctorCheck;
import org.kitout.application.doctor.DoctorContext;
import org.kitout.application.git.GitCli;
import org.kitout.application.port.CommandRunner;
import org.kitout.application.port.CommandRunner.Command;
import org.kitout.application.port.CommandRunner.Result;
import org.kitout.domain.doctor.CheckResult;
import org.kitout.domain.identity.RemoteUrlParser;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Only for developers who use SSH remotes: the key is accepted by the git server. */
@ApplicationScoped
public class SshCheck implements DoctorCheck {

    private final GitCli git;
    private final CommandRunner runner;

    public SshCheck(GitCli git, CommandRunner runner) {
        this.git = git;
        this.runner = runner;
    }

    @Override
    public String id() {
        return "ssh";
    }

    @Override
    public int order() {
        return 74;
    }

    @Override
    public boolean appliesTo(DoctorContext context) {
        return sshHost(context).isPresent();
    }

    @Override
    public List<CheckResult> run(DoctorContext context) {
        String host = sshHost(context).orElseThrow();
        Result r = runner.run(Command.of("ssh", "-T", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "git@" + host)
                .withTimeout(Duration.ofSeconds(20)));
        if (accepted(r.output())) {
            return List.of(CheckResult.ok(id(), "SSH access", host + " accepts your SSH key"));
        }
        if (r.output().contains("Host key verification failed")) {
            return List.of(CheckResult.error(id(), "SSH access", host + "'s host key is not known yet",
                    "Run once and confirm: ssh -T git@" + host));
        }
        if (r.output().contains("Permission denied")) {
            return List.of(CheckResult.error(id(), "SSH access", host + " does not accept your SSH key",
                    "Add your public key (~/.ssh/id_ed25519.pub) to your account on " + host + " (user settings, SSH keys)"));
        }
        return List.of(CheckResult.error(id(), "SSH access", "Cannot reach " + host + " over SSH: " + r.firstLine(),
                "Port 22 may be blocked; use HTTPS, the team standard"));
    }

    /**
     * What git servers answer {@code ssh -T} with when the key works (they exit with 1: no shell):
     * GitLab "Welcome to GitLab, @user!", GitHub and Gitea/Forgejo "... successfully authenticated ...",
     * Bitbucket "authenticated via ssh key".
     */
    static boolean accepted(String output) {
        String o = output.toLowerCase(java.util.Locale.ROOT);
        return o.contains("welcome to gitlab") || o.contains("successfully authenticated") || o.contains("authenticated via ssh key");
    }

    private Optional<String> sshHost(DoctorContext context) {
        return context.members().stream()
                .map(m -> git.originUrl(m.path()))
                .flatMap(Optional::stream)
                .filter(url -> !url.startsWith("http"))
                .map(RemoteUrlParser::parse)
                .flatMap(Optional::stream)
                .map(id -> id.host())
                .findFirst();
    }
}
