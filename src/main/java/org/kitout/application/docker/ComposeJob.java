package org.kitout.application.docker;

import org.kitout.application.port.ProcessControl.ProcessRef;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/** One {@code docker compose up} or {@code down}; its output goes to {@link #logFile()}. */
public final class ComposeJob {

    public enum Action { UP, DOWN }

    public enum Status { RUNNING, SUCCEEDED, FAILED }

    private final String id;
    private final String environmentId;
    private final Action action;
    private final Path logFile;
    private final Instant startedAt = Instant.now();
    private volatile Status status = Status.RUNNING;
    private volatile Instant finishedAt;
    private volatile Integer exitCode;
    private volatile ProcessRef process;

    ComposeJob(String id, String environmentId, Action action, Path logFile) {
        this.id = id;
        this.environmentId = environmentId;
        this.action = action;
        this.logFile = logFile;
    }

    void started(ProcessRef ref) {
        this.process = ref;
    }

    void finished(int exit) {
        this.exitCode = exit;
        this.finishedAt = Instant.now();
        this.status = exit == 0 ? Status.SUCCEEDED : Status.FAILED;
    }

    public String id() {
        return id;
    }

    public String environmentId() {
        return environmentId;
    }

    public Action action() {
        return action;
    }

    public Path logFile() {
        return logFile;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Status status() {
        return status;
    }

    public Optional<Instant> finishedAt() {
        return Optional.ofNullable(finishedAt);
    }

    public Optional<Integer> exitCode() {
        return Optional.ofNullable(exitCode);
    }

    Optional<ProcessRef> process() {
        return Optional.ofNullable(process);
    }
}
