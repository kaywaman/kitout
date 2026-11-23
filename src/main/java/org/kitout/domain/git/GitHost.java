package org.kitout.domain.git;

import java.util.Locale;

/**
 * Which kind of server the team's repositories are on. Cloning, pulling and reading single files
 * (the team file, compose files) work with any git server through git itself. The kind adds the
 * extras a server's API makes possible.
 *
 * <ul>
 *   <li>{@link #GITLAB}: group listing for "Clone a group", token check, files read through the API
 *       with the token (faster than git), container registry {@code registry.<host>}</li>
 *   <li>{@link #GITHUB}: organization and user listing, registry {@code ghcr.io} on github.com</li>
 *   <li>{@link #GIT}: any other git server (Gitea, Forgejo, Bitbucket, a plain SSH server)</li>
 * </ul>
 */
public enum GitHost {
    GITLAB("GitLab"), GITHUB("GitHub"), GIT("Git server");

    private final String label;

    GitHost(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * The kind as set ({@code gitlab}, {@code github}, {@code git}), or guessed from the host name when
     * not set: {@code github.com} and hosts with "github" in their name are GitHub, hosts with "gitlab"
     * GitLab, anything else a plain git server. A self-hosted GitLab named {@code git.example.com} needs
     * {@code gitlab} set.
     */
    public static GitHost of(String provider, String host) {
        String p = provider == null ? "" : provider.strip().toLowerCase(Locale.ROOT);
        switch (p) {
            case "gitlab":
                return GITLAB;
            case "github":
                return GITHUB;
            case "git":
                return GIT;
            case "", "auto":
                break;
            default:
                throw new IllegalArgumentException("Unknown git host kind '" + provider + "': use gitlab, github or git");
        }
        String h = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
        if (h.contains("github")) {
            return GITHUB;
        }
        if (h.contains("gitlab")) {
            return GITLAB;
        }
        return GIT;
    }

    /** True for a provider value kitout knows, including empty and {@code auto}. */
    public static boolean valid(String provider) {
        try {
            of(provider, "");
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The registry a team on this host usually uses; empty when there is no convention. */
    public String defaultRegistry(String host) {
        if (host == null || host.isBlank()) {
            return "";
        }
        return switch (this) {
            case GITLAB -> "registry." + host;
            case GITHUB -> host.equalsIgnoreCase("github.com") ? "ghcr.io" : "containers." + host;
            case GIT -> "";
        };
    }

    /** The HTTPS clone URL of {@code repo} ({@code group/project}) on {@code host}. */
    public static String httpsUrl(String host, String repo) {
        return "https://" + host + "/" + repo + ".git";
    }

    /** The SSH clone URL of {@code repo} on {@code host}, scp-like as servers show it. */
    public static String sshUrl(String host, String repo) {
        return "git@" + host + ":" + repo + ".git";
    }
}
