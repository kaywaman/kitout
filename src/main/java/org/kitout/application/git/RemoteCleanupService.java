package org.kitout.application.git;

import org.kitout.application.git.GitCli.RemoteUrl;
import org.kitout.application.port.CommandRunner.Result;
import org.kitout.domain.identity.Redactor;
import org.kitout.domain.identity.RemoteUrlParser;
import org.kitout.domain.identity.RepoIdentity;
import org.kitout.domain.project.Project;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The guided cleanup of tokens in remote URLs.
 *
 * <p>For each repository: rewrite every affected fetch and push URL to its clean form, test with
 * {@code git ls-remote}, and restore the original URLs if the test fails. Original URLs are
 * held in memory only for that rollback; they never appear in results, logs or responses.
 */
@ApplicationScoped
public class RemoteCleanupService {

    /** A remote URL with credentials. Deliberately without the original URL. */
    public record Finding(String serviceId, String serviceName, String remote, String key, String cleanUrl) {

        public String describe() {
            return serviceName + ": remote '" + remote + "' (" + key + ") -> " + cleanUrl;
        }
    }

    public enum Status { CLEANED, ROLLED_BACK, FAILED }

    public record Outcome(String serviceId, String serviceName, Status status, String message) {
    }

    private final GitCli git;
    private final GitEnvironment gitEnv;
    private final CredentialService credentials;

    public RemoteCleanupService(GitCli git, GitEnvironment gitEnv, CredentialService credentials) {
        this.git = git;
        this.gitEnv = gitEnv;
        this.credentials = credentials;
    }

    public List<Finding> find(List<Project> projects) {
        List<Finding> findings = new ArrayList<>();
        for (Project p : projects) {
            for (RemoteUrl r : git.remoteUrls(p.path())) {
                if (Redactor.hasEmbeddedCredentials(r.url())) {
                    findings.add(new Finding(p.id(), p.name(), r.remote(), r.key(), Redactor.stripCredentials(r.url())));
                }
            }
        }
        return findings;
    }

    /**
     * Cleans all affected repositories of the given projects.
     *
     * @throws IllegalStateException if no token is available yet: cleaning first would break access
     */
    public List<Outcome> apply(List<Project> projects) {
        Set<String> hosts = new LinkedHashSet<>();
        find(projects).forEach(f -> RemoteUrlParser.parse(f.cleanUrl()).map(RepoIdentity::host).ifPresent(hosts::add));
        List<String> withoutToken = hosts.stream().filter(h -> credentials.token(h).isEmpty()).toList();
        if (!withoutToken.isEmpty()) {
            throw new IllegalStateException("No token is stored for " + String.join(", ", withoutToken)
                    + " yet. Store the new token first (credential helper, or kitout's token setting), then clean the remotes.");
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (Project p : projects) {
            cleanRepository(p).ifPresent(outcomes::add);
        }
        return outcomes;
    }

    private Optional<Outcome> cleanRepository(Project p) {
        List<RemoteUrl> affected = git.remoteUrls(p.path()).stream()
                .filter(r -> Redactor.hasEmbeddedCredentials(r.url()))
                .toList();
        if (affected.isEmpty()) {
            return Optional.empty();
        }
        for (RemoteUrl r : affected) {
            if (!git.setRemoteUrl(p.path(), r, Redactor.stripCredentials(r.url()))) {
                restore(p, affected);
                return Optional.of(new Outcome(p.id(), p.name(), Status.FAILED, "git remote set-url failed; nothing changed"));
            }
        }
        Result test = git.lsRemote(p.path(), gitEnv.env(), gitEnv.removed());
        if (!test.succeeded()) {
            restore(p, affected);
            return Optional.of(new Outcome(p.id(), p.name(), Status.ROLLED_BACK,
                    "Access test failed, old URL restored: " + Redactor.redact(test.firstLine())));
        }
        return Optional.of(new Outcome(p.id(), p.name(), Status.CLEANED, affected.size() + " URL(s) cleaned and tested"));
    }

    private void restore(Project p, List<RemoteUrl> originals) {
        originals.forEach(r -> git.setRemoteUrl(p.path(), r, r.url()));
    }
}
