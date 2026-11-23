package org.kitout.domain.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What kitout reads from a Spring Boot project's configuration, the way Spring Boot reads it.
 * Pure: the caller merges the files ({@link #merge}) and passes them as a {@link QuarkusConfig}
 * without a profile, which only does the {@code ${key:default}} placeholders here.
 *
 * <ul>
 *   <li>Files: {@code application.properties|yml|yaml}, then {@code application-<profile>.properties|yml|yaml}
 *       on top, properties over YAML within each (as Spring Boot orders them).</li>
 *   <li>Port: {@code server.port}, default 8080.</li>
 *   <li>Readiness: on {@code management.server.port} (default: the server port), below
 *       {@code management.server.base-path} when that port is separate, else below
 *       {@code server.servlet.context-path}; then {@code management.endpoints.web.base-path}
 *       (default {@code /actuator}), then {@code /health/readiness} when the probes are enabled
 *       ({@code management.endpoint.health.probes.enabled} or {@code management.health.probes.enabled}),
 *       else {@code /health}. A service without actuator answers 404: "No health check".</li>
 * </ul>
 *
 * Not read: profile-specific documents inside one YAML file ({@code spring.config.activate.on-profile}),
 * {@code spring.config.import}, and config outside {@code src/main/resources}.
 */
public final class SpringBootConfig {

    public static final int DEFAULT_PORT = 8080;

    private SpringBootConfig() {
    }

    /** Base files first, the profile's files on top. Each map is one file, already parsed, in Spring's order. */
    public static Map<String, String> merge(List<Map<String, String>> baseFiles, List<Map<String, String>> profileFiles) {
        Map<String, String> merged = new java.util.HashMap<>();
        baseFiles.forEach(merged::putAll);
        profileFiles.forEach(merged::putAll);
        return merged;
    }

    public static int port(QuarkusConfig config) {
        return config.getInt("server.port").orElse(DEFAULT_PORT);
    }

    public static boolean portIsAdvisory(QuarkusConfig config) {
        return config.isUnresolved("server.port");
    }

    public static HealthEndpoint health(QuarkusConfig config) {
        int serverPort = port(config);
        int managementPort = config.getInt("management.server.port").orElse(serverPort);
        boolean separate = managementPort != serverPort;
        String root = separate ? config.get("management.server.base-path").orElse("/")
                : config.get("server.servlet.context-path").orElse("/");
        String base = config.get("management.endpoints.web.base-path").orElse("/actuator");
        boolean probes = config.get("management.endpoint.health.probes.enabled")
                .or(() -> config.get("management.health.probes.enabled"))
                .map(Boolean::parseBoolean).orElse(false);
        String path = HealthEndpoint.join(root, relative(base), probes ? "health/readiness" : "health");
        return new HealthEndpoint(managementPort, path,
                config.isUnresolved("management.server.port") || config.isUnresolved("server.port"));
    }

    /** Spring's base path is relative to the root even when written with a leading slash. */
    private static String relative(String path) {
        return path.replaceFirst("^/+", "");
    }

    /** Databases and brokers from the usual Spring keys. */
    public static List<Endpoint> infrastructure(QuarkusConfig config) {
        List<Endpoint> result = new ArrayList<>();
        config.get("spring.datasource.url").flatMap(u -> EndpointExtractor.parseDatabaseUrl(u, "spring.datasource.url")).ifPresent(result::add);
        for (String key : List.of("spring.data.mongodb.uri", "spring.mongodb.uri")) {
            config.get(key).ifPresent(v -> result.addAll(EndpointExtractor.parseHostList(
                    v.replaceFirst("^mongodb(\\+srv)?://([^@/]*@)?", "").replaceFirst("[/?].*$", ""), "mongodb", key)));
        }
        config.get("spring.data.mongodb.host").ifPresent(h -> result.add(new Endpoint("mongodb", h,
                config.getInt("spring.data.mongodb.port").orElse(27017), "spring.data.mongodb.host")));
        config.get("spring.kafka.bootstrap-servers")
                .ifPresent(v -> result.addAll(EndpointExtractor.parseHostList(v, "kafka", "spring.kafka.bootstrap-servers")));
        return result;
    }
}
