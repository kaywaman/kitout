package org.kitout.infrastructure.process;

import org.kitout.application.port.ProcessControl.ProcessRef;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real processes: a group is started detached, logs to its file, and stops as a whole tree. */
class PosixProcessControlTest {

    private final PosixProcessControl control = new PosixProcessControl(new ProcessCommandRunner());

    @Test
    void stopsTheWholeProcessTree() throws Exception {
        Path dir = Files.createTempDirectory("kitout-proc");
        Path log = dir.resolve("logs/svc.log");
        // a parent that starts a child, like mvnw starting a JVM
        ProcessRef ref = control.spawn(List.of("sh", "-c", "echo started; sleep 300 & wait"), dir, Map.of(), log);
        waitFor(() -> readQuietly(log).contains("started"));
        assertTrue(control.isAlive(ref));

        control.terminate(ref, Duration.ofSeconds(5));

        waitFor(() -> !control.isAlive(ref));
        assertFalse(control.isAlive(ref));
    }

    @Test
    void reportsExitCode() throws Exception {
        Path dir = Files.createTempDirectory("kitout-proc");
        ProcessRef ref = control.spawn(List.of("sh", "-c", "exit 3"), dir, Map.of(), dir.resolve("x.log"));
        waitFor(() -> !control.isAlive(ref));
        assertEquals(3, control.exitCode(ref).orElse(-1));
    }

    @Test
    void neverAdoptsARecycledPid() {
        long myPid = ProcessHandle.current().pid();
        ProcessRef stale = new ProcessRef(myPid, Instant.parse("2001-01-01T00:00:00Z"));
        assertFalse(control.isAlive(stale), "a pid with another start time is not ours");
    }

    private static String readQuietly(Path p) {
        try {
            return Files.exists(p) ? Files.readString(p) : "";
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) {
            Thread.sleep(100);
        }
    }
}
