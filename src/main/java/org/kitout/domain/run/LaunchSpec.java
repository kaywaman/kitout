package org.kitout.domain.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything needed to start a service process: an argument list (never a shell string),
 * a working directory and extra environment.
 *
 * @param containerName set for {@link RunMode#IMAGE}; used to stop and re-adopt the container
 * @param pullImage     image to pull before starting, if any
 */
public record LaunchSpec(
        List<String> command,
        Path workingDirectory,
        Map<String, String> environment,
        Optional<String> containerName,
        Optional<String> pullImage) {

    public LaunchSpec {
        command = List.copyOf(command);
        environment = Map.copyOf(environment);
    }
}
