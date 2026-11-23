package org.kitout.application.run;

import org.kitout.application.config.ConfigRepository;
import org.kitout.application.doctor.DoctorService;
import org.kitout.application.doctor.DoctorService.Report;
import org.kitout.application.workspace.GroupService;
import org.kitout.domain.group.Group;
import org.kitout.domain.run.RunMode;
import org.kitout.domain.run.ServiceState;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Starts and stops whole groups in tiers: services first, healthy, then the BFF.
 * A service that fails is a warning, not a block, so a developer can still run the BFF with
 * only some services. Doctor errors block the start unless forced.
 */
@ApplicationScoped
public class GroupRunner {

    static final Duration POLL = Duration.ofSeconds(1);

    /** Progress of one group start, readable by the UI while it runs. */
    public static final class Progress {
        private final String group;
        private final Instant startedAt = Instant.now();
        private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean finished;
        private volatile boolean blocked;

        Progress(String group) {
            this.group = group;
        }

        public String group() {
            return group;
        }

        public Instant startedAt() {
            return startedAt;
        }

        public List<String> messages() {
            synchronized (messages) {
                return List.copyOf(messages);
            }
        }

        public boolean finished() {
            return finished;
        }

        public boolean blocked() {
            return blocked;
        }

        void add(String message) {
            messages.add(message);
        }
    }

    /** Thrown when the doctor finds errors and the start was not forced. */
    public static class BlockedByDoctorException extends RuntimeException {
        private final transient Report report;

        public BlockedByDoctorException(Report report) {
            super("The doctor found errors. Fix them, or start anyway (override).");
            this.report = report;
        }

        public Report report() {
            return report;
        }
    }

    private final GroupService groups;
    private final ServiceSupervisor supervisor;
    private final DoctorService doctor;
    private final ConfigRepository config;
    private final Map<String, Progress> progressByGroup = new ConcurrentHashMap<>();

    public GroupRunner(GroupService groups, ServiceSupervisor supervisor, DoctorService doctor, ConfigRepository config) {
        this.groups = groups;
        this.supervisor = supervisor;
        this.doctor = doctor;
        this.config = config;
    }

    /** Starts the group in the background; follow it with {@link #progress(String)}. */
    public Progress startAsync(String groupName, boolean force) {
        Group group = groups.require(groupName);
        gate(groupName, force);
        Progress progress = new Progress(groupName);
        progressByGroup.put(groupName, progress);
        Thread.ofVirtual().name("group-start-" + groupName).start(() -> runTiers(group, progress, progress::add));
        return progress;
    }

    /** Starts the group and waits; used by {@code kitout up}. */
    public Progress startAndWait(String groupName, boolean force, Consumer<String> listener) {
        Group group = groups.require(groupName);
        gate(groupName, force);
        Progress progress = new Progress(groupName);
        runTiers(group, progress, m -> {
            progress.add(m);
            listener.accept(m);
        });
        return progress;
    }

    public Optional<Progress> progress(String groupName) {
        return Optional.ofNullable(progressByGroup.get(groupName));
    }

    public void stop(String groupName, Consumer<String> listener) {
        Group group = groups.require(groupName);
        for (String id : group.stopOrder()) {
            if (supervisor.status(id).mode() != RunMode.IDE) {   // includes processes started before a switch to IDE
                ServiceStatus s = supervisor.stop(id);
                listener.accept(s.name() + ": " + s.state());
            }
        }
    }

    private void gate(String groupName, boolean force) {
        if (force) {
            return;
        }
        Report report = doctor.run(Optional.of(groupName));
        if (report.hasErrors()) {
            throw new BlockedByDoctorException(report);
        }
    }

    private void runTiers(Group group, Progress progress, Consumer<String> say) {
        try {
            List<List<String>> tiers = group.startTiers();
            for (int i = 0; i < tiers.size(); i++) {
                List<String> tier = tiers.get(i);
                boolean isBffTier = group.isBffTier(tier);
                say.accept((isBffTier ? (tier.size() == 1 ? "Starting the BFF: " : "Starting the BFFs: ") : "Starting tier " + (i + 1) + ": ")
                        + String.join(", ", tier));
                List<String> started = new ArrayList<>();
                for (String id : tier) {
                    if (startOne(id, say)) {
                        started.add(id);
                    }
                }
                waitForTier(started, say);
            }
            say.accept("Done in " + ServiceSupervisor.formatSeconds(java.time.Duration.between(progress.startedAt(), Instant.now()))
                    + ".");
        } catch (RuntimeException e) {
            say.accept("Stopped early: " + e.getMessage());
        } finally {
            progress.finished = true;
        }
    }

    /** Starts one member; IDE members are only checked. Returns true if there is something to wait for. */
    private boolean startOne(String id, Consumer<String> say) {
        if (config.load().serviceSettings(id).runMode() == RunMode.IDE) {
            ServiceStatus s = supervisor.status(id);
            say.accept(s.name() + ": " + s.detail());
            return false;
        }
        try {
            supervisor.start(id);
            return true;
        } catch (IllegalStateException e) {
            say.accept("Warning: " + e.getMessage());
            return false;
        }
    }

    private void waitForTier(List<String> tier, Consumer<String> say) {
        int timeout = tier.stream().mapToInt(id -> config.load().serviceSettings(id).startTimeoutSeconds()).max().orElse(120);
        Instant deadline = Instant.now().plusSeconds(timeout + 5L);
        List<String> pending = new ArrayList<>(tier);
        while (!pending.isEmpty() && Instant.now().isBefore(deadline)) {
            for (String id : List.copyOf(pending)) {
                ServiceStatus s = supervisor.status(id);
                if (s.state() == ServiceState.RUNNING) {
                    say.accept(s.name() + " is running on port " + s.port()
                            + s.startupTime().map(d -> ", started in " + ServiceSupervisor.formatSeconds(d)).orElse("")
                            + (s.health() == org.kitout.domain.run.ServiceHealth.UNHEALTHY ? " (" + s.detail() + ")" : ""));
                    pending.remove(id);
                } else if (s.state() == ServiceState.FAILED || s.state() == ServiceState.STOPPED) {
                    say.accept("Warning: " + s.name() + " did not start: " + s.detail());
                    pending.remove(id);
                }
            }
            sleep();
        }
        pending.forEach(id -> say.accept("Warning: " + supervisor.status(id).name() + " is still not healthy"));
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted");
        }
    }
}
