package org.kitout.domain.docker;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * One line of {@code docker ps}, read with {@link #FORMAT} (tab-separated, so no JSON parser is
 * needed and the native build stays simple).
 *
 * @param composeFiles the compose files the container was created from (absolute paths), from the
 *                     label {@code com.docker.compose.project.config_files}; empty if not from compose
 * @param kitoutService  the kitout service this container runs, from kitout's own label; empty otherwise
 */
public record Container(String id, String name, String image, String state, String status, String ports,
                        String composeProject, List<String> composeFiles, String kitoutService) {

    /** The {@code docker ps --format} template matching {@link #parse}. */
    public static final String FORMAT = String.join("\t",
            "{{.ID}}", "{{.Names}}", "{{.Image}}", "{{.State}}", "{{.Status}}", "{{.Ports}}",
            "{{.Label \"com.docker.compose.project\"}}",
            "{{.Label \"com.docker.compose.project.config_files\"}}",
            "{{.Label \"org.kitout.service\"}}");

    public static Optional<Container> parse(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 9 || f[0].isBlank()) {
            return Optional.empty();
        }
        List<String> files = f[7].isBlank() ? List.of()
                : Arrays.stream(f[7].split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        return Optional.of(new Container(f[0].strip(), f[1].strip(), f[2].strip(), f[3].strip(), f[4].strip(),
                f[5].strip(), f[6].strip(), files, f[8].strip()));
    }

    public boolean running() {
        return state.equals("running");
    }

    /** True if compose created this container from {@code file} (compared as absolute paths). */
    public boolean createdFrom(String file) {
        return composeFiles.contains(file);
    }
}
