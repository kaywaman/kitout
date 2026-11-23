package org.kitout.application.git;

import org.kitout.application.port.CommandRunner.Result;
import org.kitout.application.port.SecretStore;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

/**
 * Where kitout gets the one git token from.
 *
 * <ol>
 *   <li>git's credential helper, via {@code git credential fill}: the same entry that git and
 *       VS Code use, so the token is stored once and a renewal reaches every tool</li>
 *   <li>kitout's own {@link SecretStore} entry, only for developers without a helper</li>
 * </ol>
 * The token is returned to callers in memory only. It is never logged, never put in a URL,
 * an argument or the environment, and never returned by the REST API.
 */
@ApplicationScoped
public class CredentialService {

    public enum Source { GIT_CREDENTIAL_HELPER, KITOUT_KEYRING, NONE }

    public record TokenLookup(Source source, Optional<String> token) {
    }

    private final GitCli git;
    private final SecretStore secrets;

    public CredentialService(GitCli git, SecretStore secrets) {
        this.git = git;
        this.secrets = secrets;
    }

    public TokenLookup lookup(String host) {
        Optional<String> fromHelper = fromCredentialHelper(host);
        if (fromHelper.isPresent()) {
            return new TokenLookup(Source.GIT_CREDENTIAL_HELPER, fromHelper);
        }
        Optional<String> fromStore = secrets.lookup(host);
        return fromStore.isPresent()
                ? new TokenLookup(Source.KITOUT_KEYRING, fromStore)
                : new TokenLookup(Source.NONE, Optional.empty());
    }

    public Optional<String> token(String host) {
        return lookup(host).token();
    }

    public Optional<String> fromCredentialHelper(String host) {
        Result r = git.credentialFill(host);
        if (!r.succeeded()) {
            return Optional.empty();
        }
        for (String line : r.output().split("\\R")) {
            if (line.startsWith("password=") && line.length() > "password=".length()) {
                return Optional.of(line.substring("password=".length()));
            }
        }
        return Optional.empty();
    }

    public boolean hasFallbackToken(String host) {
        return secrets.lookup(host).isPresent();
    }

    public void storeFallbackToken(String host, String token) {
        if (token == null || token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("The token must not be empty or contain spaces");
        }
        secrets.store(host, token.strip());
    }

    public void deleteFallbackToken(String host) {
        secrets.delete(host);
    }

    public String fallbackLocation() {
        return secrets.location();
    }

    /**
     * Answer for git's askpass prompt. Uses kitout's own store only: git has already asked its
     * credential helpers before falling back to askpass, so asking them again would loop.
     */
    public Optional<String> askpassAnswer(String prompt, String host) {
        if (prompt == null) {
            return Optional.empty();
        }
        if (prompt.startsWith("Username")) {
            return Optional.of("oauth2");
        }
        if (prompt.startsWith("Password")) {
            return secrets.lookup(host);
        }
        return Optional.empty();
    }
}
