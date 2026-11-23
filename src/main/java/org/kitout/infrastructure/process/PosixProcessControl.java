package org.kitout.infrastructure.process;

import org.kitout.application.port.CommandRunner;
import org.kitout.application.port.CommandRunner.Command;
import org.kitout.application.port.ProcessControl;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process groups on Linux.
 *
 * <p>{@code setsid} starts the command as the leader of a new session and process group, so its
 * group id equals its pid, and it does not receive signals meant for kitout. Stopping sends the
 * signal to the negative pid (the whole group), which also reaches the JVMs and containers the
 * command started. Signals are sent with the {@code kill} command to stay native-image friendly.
 */
@ApplicationScoped
public class PosixProcessControl implements ProcessControl {

    private static final Duration POLL = Duration.ofMillis(200);
    private final CommandRunner runner;
    private final Map<Long, Process> spawnedHere = new ConcurrentHashMap<>();

    public PosixProcessControl(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public ProcessRef spawn(List<String> command, Path workingDirectory, Map<String, String> environment, Path logFile) {
        List<String> args = new ArrayList<>();
        args.add("setsid");
        args.addAll(command);
        try {
            Files.createDirectories(logFile.getParent());
            ProcessBuilder pb = new ProcessBuilder(args)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            pb.environment().putAll(environment);
            Process process = pb.start();
            spawnedHere.put(process.pid(), process);
            Instant started = process.info().startInstant().orElse(Instant.now()).truncatedTo(ChronoUnit.SECONDS);
            return new ProcessRef(process.pid(), started);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start " + command.get(0) + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAlive(ProcessRef ref) {
        if (ref.pgid() <= 0) {
            return false;
        }
        Optional<ProcessHandle> leader = ProcessHandle.of(ref.pgid());
        if (leader.isPresent() && leader.get().isAlive()) {
            // a recycled pid belongs to another process: never adopt or signal it
            Optional<Instant> start = leader.get().info().startInstant().map(i -> i.truncatedTo(ChronoUnit.SECONDS));
            if (start.isPresent() && Math.abs(Duration.between(start.get(), ref.startedAt()).toSeconds()) > 2) {
                return false;
            }
            return true;
        }
        // leader gone, but members of its group may still run (e.g. a forked JVM)
        return groupExists(ref.pgid());
    }

    @Override
    public void terminate(ProcessRef ref, Duration grace) {
        if (!isAlive(ref)) {
            return;
        }
        signal(ref.pgid(), "TERM");
        Instant deadline = Instant.now().plus(grace);
        while (Instant.now().isBefore(deadline) && groupExists(ref.pgid())) {
            sleep();
        }
        if (groupExists(ref.pgid())) {
            signal(ref.pgid(), "KILL");
        }
    }

    @Override
    public Optional<Integer> exitCode(ProcessRef ref) {
        Process p = spawnedHere.get(ref.pgid());
        if (p == null || p.isAlive()) {
            return Optional.empty();
        }
        spawnedHere.remove(ref.pgid());
        return Optional.of(p.exitValue());
    }

    private boolean groupExists(long pgid) {
        return runner.run(Command.of("kill", "-0", "--", "-" + pgid)).succeeded();
    }

    private void signal(long pgid, String signal) {
        runner.run(Command.of("kill", "-" + signal, "--", "-" + pgid));
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
