package org.kitout.domain.config;

/**
 * Where a service answers its readiness check, read from its configuration the way Quarkus
 * builds the URL:
 *
 * <ul>
 *   <li>without the management interface: {@code quarkus.http.root-path} (default {@code /}),
 *       then {@code quarkus.http.non-application-root-path} (default {@code q}), then
 *       {@code quarkus.smallrye-health.root-path} (default {@code health}), then
 *       {@code quarkus.smallrye-health.readiness-path} (default {@code ready}), on the HTTP port</li>
 *   <li>with {@code quarkus.management.enabled=true}: {@code quarkus.management.root-path}
 *       (default {@code /q}) on {@code quarkus.management.port} (default 9000); the HTTP root
 *       path does not apply</li>
 * </ul>
 * A segment starting with {@code /} is absolute and replaces everything before it, as in Quarkus.
 *
 * @param advisory true when part of the URL depends on an environment variable
 */
public record HealthEndpoint(int port, String path, boolean advisory) {

    public static final int MANAGEMENT_DEFAULT_PORT = 9000;

    public static HealthEndpoint of(QuarkusConfig config, int httpPort) {
        boolean management = config.get("quarkus.management.enabled").map(Boolean::parseBoolean).orElse(false);
        String health = config.get("quarkus.smallrye-health.root-path").orElse("health");
        String readiness = config.get("quarkus.smallrye-health.readiness-path").orElse("ready");
        boolean advisory = config.isUnresolved("quarkus.smallrye-health.root-path")
                || config.isUnresolved("quarkus.smallrye-health.readiness-path");
        if (management) {
            int port = config.getInt("quarkus.management.port").orElse(MANAGEMENT_DEFAULT_PORT);
            String root = config.get("quarkus.management.root-path").orElse("/q");
            return new HealthEndpoint(port, join("/", root, health, readiness),
                    advisory || config.isUnresolved("quarkus.management.port"));
        }
        String httpRoot = config.get("quarkus.http.root-path").orElse("/");
        String nonAppRoot = config.get("quarkus.http.non-application-root-path").orElse("q");
        return new HealthEndpoint(httpPort, join(httpRoot, nonAppRoot, health, readiness),
                advisory || config.isUnresolved("quarkus.http.non-application-root-path"));
    }

    /** Joins path segments; an absolute segment ({@code /x}) restarts the path. */
    static String join(String... segments) {
        String path = "/";
        for (String raw : segments) {
            String s = raw == null ? "" : raw.strip();
            if (s.isEmpty()) {
                continue;
            }
            if (s.startsWith("/")) {
                path = s;
            } else {
                path = (path.endsWith("/") ? path : path + "/") + s;
            }
        }
        String normalized = path.replaceAll("/{2,}", "/");
        return normalized.length() > 1 && normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }
}
