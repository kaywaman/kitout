package org.kitout.application.build;

import org.kitout.application.port.ProcessControl.ProcessRef;
import org.kitout.domain.build.BuildRequest;
import org.kitout.domain.build.BuildStatus;
import org.kitout.domain.build.NativeBuildErrorExplainer.Explanation;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/** One build, from queued to finished. Mutable on purpose: the build thread updates it, the API reads it. */
public final class BuildJob {

    private final String id;
    private final String serviceName;
    private final BuildRequest request;
    private final Path logFile;
    private volatile Instant createdAt = Instant.now();
    private volatile BuildStatus status = BuildStatus.QUEUED;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile Explanation explanation;
    private volatile String waitingFor;
    private volatile ProcessRef process;
    private volatile boolean cancelRequested;
    private final String version;
    private volatile boolean discovered;
    private volatile Path artifact;
    private volatile Runnable onFinish = () -> { };

    BuildJob(String id, String serviceName, BuildRequest request, Path logFile) {
        this(id, serviceName, request, logFile, null);
    }

    /** @param version the project's version when the build was started; null if unknown */
    BuildJob(String id, String serviceName, BuildRequest request, Path logFile, String version) {
        this.version = version;
        this.id = id;
        this.serviceName = serviceName;
        this.request = request;
        this.logFile = logFile;
    }

    /**
     * A finished build read back after a kitout restart, or build output kitout found in a project
     * ({@code discovered}: built outside kitout or before its history existed; no log).
     */
    static BuildJob restored(String id, String serviceName, BuildRequest request, Path logFile, String version, BuildStatus status,
                             Instant created, Instant started, Instant finished, Explanation explanation, boolean discovered, Path artifact) {
        BuildJob j = new BuildJob(id, serviceName, request, logFile, version);
        j.createdAt = created;
        j.startedAt = started;
        j.finishedAt = finished;
        j.explanation = explanation;
        j.status = status;
        j.discovered = discovered;
        j.artifact = artifact;
        return j;
    }

    public String id() {
        return id;
    }

    /** Found in the project's build output, not built by kitout: no log, profile unknown. */
    public boolean discovered() {
        return discovered;
    }

    public Optional<Path> artifact() {
        return Optional.ofNullable(artifact);
    }

    void onFinish(Runnable r) {
        onFinish = r;
    }

    public Optional<String> version() {
        return Optional.ofNullable(version);
    }

    public String serviceName() {
        return serviceName;
    }

    public BuildRequest request() {
        return request;
    }

    public Path logFile() {
        return logFile;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public BuildStatus status() {
        return status;
    }

    public Optional<Instant> startedAt() {
        return Optional.ofNullable(startedAt);
    }

    public Optional<Instant> finishedAt() {
        return Optional.ofNullable(finishedAt);
    }

    public Optional<Explanation> explanation() {
        return Optional.ofNullable(explanation);
    }

    /** Why a queued build is still waiting, e.g. "another native build in a different kitout window". */
    public Optional<String> waitingFor() {
        return Optional.ofNullable(waitingFor);
    }

    boolean cancelRequested() {
        return cancelRequested;
    }

    Optional<ProcessRef> process() {
        return Optional.ofNullable(process);
    }

    void requestCancel() {
        cancelRequested = true;
    }

    void waiting(String reason) {
        waitingFor = reason;
    }

    void running(ProcessRef ref) {
        process = ref;
        waitingFor = null;
        startedAt = Instant.now();
        status = BuildStatus.RUNNING;
    }

    void finish(BuildStatus finalStatus, Explanation why) {
        explanation = why;
        waitingFor = null;
        finishedAt = Instant.now();
        status = finalStatus;
        onFinish.run();
    }
}
