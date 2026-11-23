package org.kitout.application.run;

import org.kitout.domain.run.RunMode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Which services kitout has started, stored apart from the configuration in
 * {@code ~/.local/state/kitout/state.json}, so a crash never corrupts settings.
 *
 * <p>An entry exists from "start" until "stop". An entry whose process group is gone without a
 * stop is a crash, shown as FAILED until the developer starts or stops the service again.
 */
public record RunState(Map<String, Run> runs) {

    public enum Phase { PULLING, STARTED }

    /**
     * @param pgid          process group id; 0 while the image is being pulled
     * @param processStart  start time of the group leader, to detect pid reuse
     * @param containerName set for image runs
     * @param readyAt       when the readiness check first answered; null while starting.
     *                      {@code readyAt - startedAt} is the start time shown to the developer.
     */
    public record Run(long pgid, Instant processStart, RunMode mode, Instant startedAt, int port,
                      String containerName, Phase phase, int startTimeoutSeconds, Instant readyAt) {

        public java.util.Optional<java.time.Duration> startupTime() {
            return readyAt == null ? java.util.Optional.empty() : java.util.Optional.of(java.time.Duration.between(startedAt, readyAt));
        }

        public Run ready(Instant at) {
            return new Run(pgid, processStart, mode, startedAt, port, containerName, phase, startTimeoutSeconds, at);
        }
    }

    public RunState {
        runs = runs == null ? Map.of() : Map.copyOf(runs);
    }

    public static RunState empty() {
        return new RunState(Map.of());
    }

    public Optional<Run> run(String serviceId) {
        return Optional.ofNullable(runs.get(serviceId));
    }

    public RunState with(String serviceId, Run run) {
        Map<String, Run> copy = new LinkedHashMap<>(runs);
        copy.put(serviceId, run);
        return new RunState(copy);
    }

    public RunState without(String serviceId) {
        Map<String, Run> copy = new LinkedHashMap<>(runs);
        copy.remove(serviceId);
        return new RunState(copy);
    }
}
