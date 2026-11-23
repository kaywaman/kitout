package org.kitout.domain.run;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which local versions of one service image to remove, so pulled images do not pile up: the newest
 * {@code keep} versions stay, and so does any version in use. A version is one image id; a tag the
 * pipeline moved leaves the old image as {@code <none>}, which counts as an older version.
 */
public final class ImageRetention {

    /** One local image of the repository, as {@code docker images} lists it. */
    public record Version(String id, String tag, String createdAt) {
    }

    private ImageRetention() {
    }

    /**
     * @param versions all local images of one repository (one entry per tag; {@code <none>} for untagged)
     * @param keep     how many versions to keep (at least 1)
     * @param inUse    image ids to keep whatever their age: the one just started, those of running containers
     * @return what to remove: {@code repository:tag} for tagged images (removing the tag frees the image),
     *         the image id for untagged ones; oldest first
     */
    public static List<String> toRemove(String repository, List<Version> versions, int keep, Set<String> inUse) {
        Map<String, List<Version>> byId = new LinkedHashMap<>();
        versions.stream()
                .sorted(Comparator.comparing(Version::createdAt).reversed())   // ISO-like "2026-10-31 14:02:11 +0100 CET" sorts by text
                .forEach(v -> byId.computeIfAbsent(shortId(v.id()), k -> new ArrayList<>()).add(v));
        List<String> ids = new ArrayList<>(byId.keySet());
        Set<String> keepIds = new java.util.HashSet<>(ids.subList(0, Math.min(ids.size(), Math.max(1, keep))));
        inUse.forEach(id -> keepIds.add(shortId(id)));   // in use: kept in addition, docker would refuse anyway
        List<String> remove = new ArrayList<>();
        for (int i = ids.size() - 1; i >= 0; i--) {
            String id = ids.get(i);
            if (keepIds.contains(id)) {
                continue;
            }
            for (Version v : byId.get(id)) {
                remove.add("<none>".equals(v.tag()) ? id : repository + ":" + v.tag());
            }
        }
        return remove;
    }

    private static String shortId(String id) {
        String plain = id.startsWith("sha256:") ? id.substring(7) : id;
        return plain.length() > 12 ? plain.substring(0, 12) : plain;
    }
}
