package org.kitout.application.run;

import org.kitout.domain.run.RunMode;
import org.kitout.domain.run.ServiceHealth;
import org.kitout.domain.run.ServiceState;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * What the UI and CLI show for one service.
 *
 * @param detail      one sentence for the developer, e.g. "Pulling the image" or "Not ready: Database connections health check"
 * @param health      readiness, separate from the state: a RUNNING service can be UNHEALTHY
 * @param startupTime from process start until the readiness check first answered; empty while starting and for IDE runs
 */
public record ServiceStatus(
        String serviceId,
        String name,
        ServiceState state,
        RunMode mode,
        int port,
        String detail,
        Optional<Instant> startedAt,
        ServiceHealth health,
        Optional<Duration> startupTime) {

    public ServiceStatus withDetail(String newDetail) {
        return new ServiceStatus(serviceId, name, state, mode, port, newDetail, startedAt, health, startupTime);
    }
}
