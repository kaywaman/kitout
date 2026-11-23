package org.kitout.application.doctor.checks;

import org.kitout.application.doctor.DoctorCheck;
import org.kitout.application.doctor.DoctorContext;
import org.kitout.application.git.CredentialService;
import org.kitout.application.git.CredentialService.Source;
import org.kitout.application.git.CredentialService.TokenLookup;
import org.kitout.application.port.GitLabApi;
import org.kitout.application.port.GitLabApi.TokenInfo;
import org.kitout.domain.doctor.CheckResult;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The one GitLab token: found, valid, scoped narrowly (no {@code api}), with an expiry, and
 * warned about 14 days before it expires.
 */
@ApplicationScoped
public class GitLabTokenCheck implements DoctorCheck {

    static final List<String> REQUIRED = List.of("read_api", "read_repository");
    static final int EXPIRY_WARNING_DAYS = 14;
    private static final String TITLE = "GitLab token";
    private static final String CREATE = "Create a personal access token with read_api, read_repository and "
            + "write_repository and an expiry date, then store it once (see the credential guide)";

    private final CredentialService credentials;
    private final GitLabApi gitlab;

    public GitLabTokenCheck(CredentialService credentials, GitLabApi gitlab) {
        this.credentials = credentials;
        this.gitlab = gitlab;
    }

    @Override
    public String id() {
        return "gitlab-token";
    }

    @Override
    public int order() {
        return 71;
    }

    @Override
    public boolean appliesTo(DoctorContext context) {
        return context.isGitLab();   // GitHub and plain git servers have no token API kitout can ask
    }

    @Override
    public List<CheckResult> run(DoctorContext context) {
        String host = context.config().gitlab().host();
        TokenLookup lookup = credentials.lookup(host);
        if (lookup.token().isEmpty()) {
            return List.of(CheckResult.warning(id(), TITLE, "No token found for " + host, CREATE));
        }
        TokenInfo info;
        try {
            info = gitlab.tokenInfo(host, lookup.token().get());
        } catch (GitLabApi.GitLabException e) {
            return List.of(e.status() == 401
                    ? CheckResult.warning(id(), TITLE, "GitLab rejects the stored token (revoked or expired)", CREATE)
                    : CheckResult.warning(id(), TITLE, "Could not check the token: " + e.getMessage(), "Run the doctor again later"));
        }
        return evaluate(info, lookup.source(), LocalDate.now());
    }

    List<CheckResult> evaluate(TokenInfo info, Source source, LocalDate today) {
        List<CheckResult> results = new ArrayList<>();
        String from = source == Source.GIT_CREDENTIAL_HELPER ? "git's credential helper" : "kitout's keyring entry";
        if (!info.active()) {
            return List.of(CheckResult.warning(id(), TITLE, "The token is not active", CREATE));
        }
        List<String> missing = REQUIRED.stream().filter(s -> !info.scopes().contains(s) && !info.scopes().contains("api")).toList();
        if (!missing.isEmpty()) {
            results.add(CheckResult.warning(id(), TITLE, "Missing scopes: " + String.join(", ", missing), CREATE));
        }
        if (info.scopes().contains("api")) {
            results.add(CheckResult.warning(id(), TITLE, "The token has the broad 'api' scope",
                    "Replace it with a token limited to read_api, read_repository and write_repository"));
        }
        if (info.expiresAt().isEmpty()) {
            results.add(CheckResult.warning(id(), TITLE, "The token never expires", "Create a token with an expiry date"));
        } else {
            long days = ChronoUnit.DAYS.between(today, info.expiresAt().get());
            if (days <= EXPIRY_WARNING_DAYS) {
                results.add(CheckResult.warning(id(), TITLE, "The token expires in " + days + " days ("
                        + info.expiresAt().get() + ")", "Create a new token now and store it once; every tool picks it up"));
            }
        }
        if (source == Source.KITOUT_KEYRING) {
            results.add(CheckResult.info(id(), TITLE, "Found in kitout's keyring entry, not in git's credential helper. "
                    + "Set up the helper so git, VS Code and kitout share one token."));
        }
        if (results.isEmpty() || results.stream().allMatch(r -> r.severity() == org.kitout.domain.doctor.Severity.INFO)) {
            results.add(0, CheckResult.ok(id(), TITLE, "Valid, scopes " + String.join(", ", info.scopes())
                    + info.expiresAt().map(d -> ", expires " + d).orElse("") + " (from " + from + ")"));
        }
        return results;
    }
}
