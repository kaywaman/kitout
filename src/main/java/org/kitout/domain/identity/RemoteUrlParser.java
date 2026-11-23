package org.kitout.domain.identity;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a git remote URL into a {@link RepoIdentity}.
 *
 * <p>Supported forms (nested GitLab groups included):
 * <ul>
 *   <li>scp-like SSH: {@code git@host:group/sub/project.git}</li>
 *   <li>SSH URL: {@code ssh://git@host:2222/group/sub/project.git}</li>
 *   <li>HTTPS: {@code https://host/group/sub/project.git}, with or without {@code user:token@}</li>
 * </ul>
 * Credentials in the URL are ignored, so a repository has the same identity before and after
 * the credential cleanup. The parser never returns or logs them.
 */
public final class RemoteUrlParser {

    private static final Pattern SCP_LIKE = Pattern.compile("^(?:[^@/\\s]+@)?([^:/\\s]+):(?!//)(.+)$");

    private RemoteUrlParser() {
    }

    public static Optional<RepoIdentity> parse(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return Optional.empty();
        }
        String url = remoteUrl.trim();
        if (url.contains("://")) {
            return parseUri(url);
        }
        Matcher m = SCP_LIKE.matcher(url);
        return m.matches() ? fromHostAndPath(m.group(1), m.group(2)) : Optional.empty();
    }

    private static Optional<RepoIdentity> parseUri(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!scheme.equals("https") && !scheme.equals("http") && !scheme.equals("ssh")) {
                return Optional.empty();
            }
            return fromHostAndPath(uri.getHost(), uri.getPath());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    private static Optional<RepoIdentity> fromHostAndPath(String host, String rawPath) {
        if (host == null || rawPath == null) {
            return Optional.empty();
        }
        String path = rawPath.replaceAll("^/+", "").replaceAll("/+$", "");
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0 || lastSlash == path.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new RepoIdentity(host, path.substring(0, lastSlash), path.substring(lastSlash + 1)));
    }
}
