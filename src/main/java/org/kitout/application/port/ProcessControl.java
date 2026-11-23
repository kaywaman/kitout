package org.kitout.application.port;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Starts long-running processes in their own process group and stops whole groups.
 *
 * <p>A process group survives kitout itself: closing kitout does not stop the stack, and a restarted
 * kitout re-adopts running services from their saved group ids.
 */
public interface ProcessControl {

    /** A started process group. {@code startedAt} guards against a recycled pid being adopted. */
    record ProcessRef(long pgid, Instant startedAt) {
    }

    /** Starts the command detached, with stdout and stderr appended to {@code logFile}. */
    ProcessRef spawn(List<String> command, Path workingDirectory, Map<String, String> environment, Path logFile);

    /** True if the group's leader is the process we started and the group still exists. */
    boolean isAlive(ProcessRef ref);

    /** SIGTERM to the whole group, SIGKILL after {@code grace} if anything is left. */
    void terminate(ProcessRef ref, Duration grace);

    /** Exit code of a group we spawned in this JVM, if it has finished. */
    Optional<Integer> exitCode(ProcessRef ref);
}
