package org.kitout.domain.clone;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Where a project is cloned to: the namespace mirrored under the workspace root, e.g.
 * {@code ~/work} + {@code acme/shop/orders} = {@code ~/work/acme/shop/orders}.
 *
 * <p>The project path comes from the GitLab API, so it is untrusted input. Every segment is
 * validated, and the result must stay inside the workspace root.
 */
public final class CloneTarget {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.\\-]{0,254}");

    private CloneTarget() {
    }

    public static Path resolve(Path workspaceRoot, String projectFullPath) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        if (projectFullPath == null || projectFullPath.isBlank()) {
            throw new IllegalArgumentException("Empty project path");
        }
        for (String segment : projectFullPath.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches() || segment.equals("..") || segment.endsWith(".")) {
                throw new IllegalArgumentException("Unsafe project path segment: '" + segment + "'");
            }
        }
        Path target = root.resolve(projectFullPath).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("Project path escapes the workspace root: " + projectFullPath);
        }
        return target;
    }
}
