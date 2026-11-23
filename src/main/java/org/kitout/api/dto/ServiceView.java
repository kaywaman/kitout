package org.kitout.api.dto;

import java.time.Instant;

/**
 * One service as the UI shows it: identity, run mode, port and live state.
 *
 * @param health         HEALTHY, UNHEALTHY, NO_HEALTH_CHECK or UNKNOWN; a RUNNING service can be UNHEALTHY
 * @param healthPath     where the readiness check is asked, e.g. {@code /q/health/ready} (port {@code healthPort})
 * @param startupMillis  time from process start to the first answer of the readiness check; null while starting
 * @param image          the full image reference that runs in IMAGE mode; null when no tag is chosen
 * @param imageRepository the image repository without tag; null when none is known
 * @param imageTag       the tag chosen for IMAGE mode; null until chosen (there is no default)
 * @param envCount       how many environment variables the service has of its own (shared ones not counted)
 * @param volumes        host folders mounted into its container (registry image mode)
 * @param dockerArgs     extra docker run options as typed; null for none
 * @param healthCheck    false when the health check is switched off: started means the port answers
 * @param type           {@code quarkus}, {@code spring-boot}, {@code java}, {@code node} or {@code image}
 * @param typeLabel      for people: "Spring Boot"
 * @param runModes       where it can run from (IMAGE, JVM, NATIVE, SCRIPT, IDE), in the order to offer them
 * @param buildable      kitout builds it (Java types)
 * @param nativeBuild    it can be built natively (Quarkus)
 * @param scripts        a Node project's npm scripts that can start it
 * @param script         the script it starts with
 * @param portOverride   the port the developer set, or null for the detected one
 * @param hasPort        false for a plain Java app without a port: running means its process lives
 */
public record ServiceView(
        String id,
        String name,
        String fullPath,
        String path,
        String state,
        String mode,
        int port,
        boolean portAdvisory,
        String detail,
        Instant startedAt,
        boolean routesToOthers,
        String image,
        String runtimeProfile,
        int startTimeoutSeconds,
        String health,
        String healthPath,
        int healthPort,
        Long startupMillis,
        String imageRepository,
        String imageTag,
        int envCount,
        String version,
        java.util.List<VolumeView> volumes,
        String dockerArgs,
        boolean healthCheck,
        String type,
        String typeLabel,
        java.util.List<String> runModes,
        boolean buildable,
        boolean nativeBuild,
        java.util.List<String> scripts,
        String script,
        Integer portOverride,
        boolean hasPort) {
}
