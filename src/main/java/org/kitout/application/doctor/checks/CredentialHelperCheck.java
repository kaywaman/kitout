package org.kitout.application.doctor.checks;

import org.kitout.application.doctor.DoctorCheck;
import org.kitout.application.doctor.DoctorContext;
import org.kitout.application.git.CredentialService;
import org.kitout.domain.doctor.CheckResult;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * HTTPS users have a credential helper that returns a token for the git host, so git,
 * VS Code and kitout share one stored token.
 */
@ApplicationScoped
public class CredentialHelperCheck implements DoctorCheck {

    private final CredentialService credentials;

    public CredentialHelperCheck(CredentialService credentials) {
        this.credentials = credentials;
    }

    @Override
    public String id() {
        return "credential-helper";
    }

    @Override
    public int order() {
        return 72;
    }

    @Override
    public boolean appliesTo(DoctorContext context) {
        return context.hasGitLab() && context.config().gitlab().cloneProtocol().equals("https");
    }

    @Override
    public List<CheckResult> run(DoctorContext context) {
        String host = context.config().gitlab().host();
        if (credentials.fromCredentialHelper(host).isPresent()) {
            return List.of(CheckResult.ok(id(), "Credential helper", "git's credential helper has a token for " + host));
        }
        String detail = credentials.hasFallbackToken(host)
                ? "git's credential helper has no token for " + host + "; kitout uses its own keyring entry meanwhile"
                : "git's credential helper has no token for " + host;
        return List.of(CheckResult.warning(id(), "Credential helper", detail,
                "Set up libsecret (git config --global credential.helper libsecret) and run 'git fetch' once in a "
                        + "repository, entering the token as the password"));
    }
}
