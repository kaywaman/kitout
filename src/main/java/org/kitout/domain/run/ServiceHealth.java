package org.kitout.domain.run;

/**
 * How a service answers its readiness check. Separate from {@link ServiceState}: a service whose
 * database is down is still RUNNING (its process is alive and kitout keeps it), but UNHEALTHY.
 */
public enum ServiceHealth {
    /** Readiness check answers 200. */
    HEALTHY,
    /** Running, but the readiness check answers 503 or the service no longer answers at all. */
    UNHEALTHY,
    /** The port answers but the service has no readiness check (no quarkus-smallrye-health). */
    NO_HEALTH_CHECK,
    /** Not running, or still starting: health does not apply yet. */
    UNKNOWN
}
