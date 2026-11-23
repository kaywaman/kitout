package org.kitout.application.port;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Small, read-only network checks. kitout never opens ports for others. */
public interface NetworkProbe {

    /** Answer of a local readiness check. */
    enum Health { READY, NOT_READY, NO_HEALTH_ENDPOINT, UNREACHABLE }

    /** @param downChecks names of the checks that report DOWN, e.g. "Database connections health check" */
    record HealthCheck(Health health, List<String> downChecks) {

        public HealthCheck {
            downChecks = List.copyOf(downChecks);
        }

        public static HealthCheck of(Health health) {
            return new HealthCheck(health, List.of());
        }
    }

    boolean canConnect(String host, int port, Duration timeout);

    /** {@code GET http://localhost:<port><path>}, e.g. path {@code /q/health/ready}. */
    HealthCheck health(int port, String path);

    /** The process listening on a local port, e.g. {@code java (pid 4711)}, if it can be found. */
    Optional<String> portHolder(int port);
}
