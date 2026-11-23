package org.kitout.application.git;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.ConfigRepository;
import org.kitout.application.config.KitoutConfig.GitLabSettings;
import org.kitout.application.port.CommandRunner;
import org.kitout.application.port.GitLabApi;
import org.kitout.domain.git.GitHost;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Path;
import java.util.Optional;

/**
 * One file of a repository that may not be cloned: the team file, a team compose file.
 *
 * <p>On GitLab with a token, read through GitLab's API (one request). Everywhere else (GitHub,
 * Gitea, Bitbucket, a plain git server, or GitLab without a token), read with git itself
 * ({@link GitFileReader}), with the developer's own git login. The clone protocol is the
 * developer's (Settings, Git), so an SSH key works as well as HTTPS.
 */
@ApplicationScoped
public class RepositoryFiles {

    /** @param version the commit the file was read at */
    public record File(String text, String version) {
    }

    private final GitLabApi gitlab;
    private final CredentialService credentials;
    private final ConfigRepository config;
    private final KitoutPaths paths;
    private final GitFileReader git;

    @jakarta.inject.Inject
    public RepositoryFiles(GitLabApi gitlab, CredentialService credentials, ConfigRepository config, KitoutPaths paths,
                           CommandRunner runner, GitEnvironment environment) {
        this(gitlab, credentials, config, paths, new GitFileReader(runner, environment::env, environment.removed()));
    }

    /** @param git null: only GitLab's API (tests without git) */
    public RepositoryFiles(GitLabApi gitlab, CredentialService credentials, ConfigRepository config, KitoutPaths paths, GitFileReader git) {
        this.gitlab = gitlab;
        this.credentials = credentials;
        this.config = config;
        this.paths = paths;
        this.git = git;
    }

    /**
     * @param repo {@code group/project} (GitHub: {@code owner/repo})
     * @param ref  branch or tag; empty or {@code HEAD}: the default branch
     * @throws GitFileReader.NotFound the file is not there (or the login cannot see it)
     * @throws IllegalStateException  the server could not be read; the message says why and what helps
     */
    public File read(String host, String repo, String path, String ref) {
        GitLabSettings settings = config.load().gitlab();
        boolean configuredHost = host.equalsIgnoreCase(settings.host());
        GitHost kind = configuredHost ? settings.kind() : GitHost.of("", host);
        String branch = ref == null || ref.isBlank() ? "HEAD" : ref.strip();

        if (kind == GitHost.GITLAB && gitlab != null && credentials != null) {
            Optional<String> token = credentials.token(host);
            if (token.isPresent()) {
                try {
                    GitLabApi.FileContent c = gitlab.file(host, repo, path, branch, token.get());
                    return new File(c.text(), c.commitId());
                } catch (GitLabApi.GitLabException e) {
                    if (e.status() == 404) {
                        throw new GitFileReader.NotFound(path + " is not in " + repo + " on " + host + " at " + branch
                                + " (or the token cannot see the repository)");
                    }
                    if ((e.status() != 401 && e.status() != 403) || git == null) {
                        throw e;
                    }
                    // the token is not allowed to read files: git with the developer's own login may be
                }
            } else if (git == null) {
                throw new IllegalStateException("No token for " + host + ": store one in Settings, Git token, or sign in to git for it once");
            }
        }
        if (git == null) {
            throw new IllegalStateException("Reading files from " + host + " needs git");
        }
        String protocol = configuredHost ? settings.cloneProtocol() : "https";
        String url = protocol.equals("ssh") ? GitHost.sshUrl(host, repo) : GitHost.httpsUrl(host, repo);
        try {
            GitFileReader.Content c = git.read(cache(host, repo), url, path, branch);
            return new File(c.text(), c.commit());
        } catch (IllegalStateException e) {
            throw new IllegalStateException(e.getMessage() + hint(e.getMessage(), host, protocol), e);
        }
    }

    /** What usually helps when git could not read: sign in, or switch protocol. */
    static String hint(String why, String host, String protocol) {
        String w = why == null ? "" : why.toLowerCase(java.util.Locale.ROOT);
        if (w.contains("could not read username") || w.contains("authentication failed") || w.contains("terminal prompts disabled")) {
            return ". git has no login for " + host + ": set up a credential helper (GitHub: gh auth setup-git), store a token in"
                    + " Settings, Git token, or clone with SSH (Settings, Git)";
        }
        if (w.contains("permission denied (publickey)") || w.contains("host key verification failed")) {
            return ". SSH to " + host + " failed: add your SSH key to " + host + " (and run ssh " + host + " once to accept its host key),"
                    + " or clone with HTTPS (Settings, Git)";
        }
        if (w.contains("repository not found") || w.contains("does not appear to be a git repository") || w.contains("not found")) {
            return ". Check the repository name, and that your login on " + host + " can see it";
        }
        return "";
    }

    private Path cache(String host, String repo) {
        Path root = paths.stateDir().resolve("git-files").toAbsolutePath().normalize();
        Path dir = root.resolve(host).resolve(repo + ".git").normalize();
        if (!dir.startsWith(root.resolve(host)) || repo.contains("..")) {
            throw new IllegalArgumentException("'" + repo + "' is not a repository path");
        }
        return dir;
    }
}
