package org.kitout.application.doctor.checks;

import org.kitout.application.doctor.DoctorCheck;
import org.kitout.application.doctor.DoctorContext;
import org.kitout.application.port.GitLabApi;
import org.kitout.domain.doctor.CheckResult;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** HTTPS to the git host works: TLS (company CA) and proxy. Not for a plain git server reached over SSH only. */
@ApplicationScoped
public class GitLabConnectionCheck implements DoctorCheck {

    private final GitLabApi gitlab;

    public GitLabConnectionCheck(GitLabApi gitlab) {
        this.gitlab = gitlab;
    }

    @Override
    public String id() {
        return "gitlab-connection";
    }

    @Override
    public int order() {
        return 70;
    }

    @Override
    public boolean appliesTo(DoctorContext context) {
        return context.hasGitLab() && (context.config().gitlab().kind() != org.kitout.domain.git.GitHost.GIT
                || context.config().gitlab().cloneProtocol().equals("https"));
    }

    @Override
    public List<CheckResult> run(DoctorContext context) {
        String host = context.config().gitlab().host();
        try {
            gitlab.ping(host);
            return List.of(CheckResult.ok(id(), "Git host connection", "HTTPS to " + host + " works"));
        } catch (RuntimeException e) {
            String message = String.valueOf(e.getMessage());
            String fix = message.contains("certificate") || message.contains("PKIX") || message.contains("SSL")
                    ? "Add the company CA certificate to the system trust store (and to git: git config --global http.sslCAInfo <file>)"
                    : "Check the network and proxy settings (HTTPS_PROXY; for git also http.proxy)";
            return List.of(CheckResult.error(id(), "Git host connection", "Cannot reach " + host + ": " + message, fix));
        }
    }
}
