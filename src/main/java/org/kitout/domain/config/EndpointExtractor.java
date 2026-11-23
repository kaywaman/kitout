package org.kitout.domain.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what a Quarkus project needs from its configuration: its own dev port, the
 * infrastructure it connects to, and (for a BFF) the services it routes to.
 */
public final class EndpointExtractor {

    public static final int QUARKUS_DEFAULT_PORT = 8080;

    private static final Pattern REST_CLIENT_URL =
            Pattern.compile("^quarkus\\.rest-client\\.(\"[^\"]+\"|[^.]+)\\.(url|uri)$");
    private static final Pattern DATASOURCE_URL =
            Pattern.compile("^quarkus\\.datasource\\.(?:(\"[^\"]+\"|[^.]+)\\.)?(jdbc|reactive)\\.url$");
    private static final Map<String, Integer> DEFAULT_PORTS = Map.of(
            "postgresql", 5432, "mysql", 3306, "mariadb", 3306, "oracle", 1521,
            "sqlserver", 1433, "db2", 50000, "mongodb", 27017, "kafka", 9092, "http", 80, "https", 443);

    private EndpointExtractor() {
    }

    /** {@code %dev.quarkus.http.port}, then {@code quarkus.http.port}, then 8080 (via the config's profile). */
    public static int httpPort(QuarkusConfig config) {
        return config.getInt("quarkus.http.port").orElse(QUARKUS_DEFAULT_PORT);
    }

    /** True when the port depends on an environment variable and the result is a guess. */
    public static boolean httpPortIsAdvisory(QuarkusConfig config) {
        return config.isUnresolved("quarkus.http.port");
    }

    /** Databases and brokers the service connects to; Dev Services (no URL configured) yield nothing. */
    public static List<Endpoint> infrastructure(QuarkusConfig config) {
        List<Endpoint> result = new ArrayList<>();
        config.withPrefix("quarkus.datasource.").forEach((key, value) -> {
            if (DATASOURCE_URL.matcher(key).matches()) {
                parseDatabaseUrl(value, key).ifPresent(result::add);
            }
        });
        config.get("quarkus.mongodb.connection-string")
                .ifPresent(v -> result.addAll(parseHostList(v.replaceFirst("^mongodb(\\+srv)?://([^@/]*@)?", "")
                        .replaceFirst("[/?].*$", ""), "mongodb", "quarkus.mongodb.connection-string")));
        config.get("quarkus.mongodb.hosts")
                .ifPresent(v -> result.addAll(parseHostList(v, "mongodb", "quarkus.mongodb.hosts")));
        config.get("kafka.bootstrap.servers")
                .ifPresent(v -> result.addAll(parseHostList(v, "kafka", "kafka.bootstrap.servers")));
        return result;
    }

    /** Rest client name (unquoted) to configured URL, e.g. {@code orders -> http://localhost:8081}. */
    public static Map<String, String> restClientUrls(QuarkusConfig config) {
        Map<String, String> result = new LinkedHashMap<>();
        config.withPrefix("quarkus.rest-client.").forEach((key, value) -> {
            Matcher m = REST_CLIENT_URL.matcher(key);
            if (m.matches()) {
                result.put(m.group(1).replace("\"", ""), value);
            }
        });
        return result;
    }

    /** host and port of an http(s) URL, with the scheme's default port. */
    public static Optional<Endpoint> parseHttpUrl(String url, String source) {
        try {
            URI uri = URI.create(url.trim());
            if (uri.getHost() == null || uri.getScheme() == null) {
                return Optional.empty();
            }
            int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_PORTS.getOrDefault(uri.getScheme().toLowerCase(), 80);
            return Optional.of(new Endpoint("http", uri.getHost(), port, source));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    static Optional<Endpoint> parseDatabaseUrl(String url, String source) {
        // jdbc:postgresql://host:5432/db, postgresql://host/db, vertx-reactive:postgresql://...
        String u = url.trim().replaceFirst("^jdbc:", "").replaceFirst("^vertx-reactive:", "");
        Matcher m = Pattern.compile("^([a-z0-9]+)(?::[a-z]+)?://(?:[^@/]*@)?([^/:?;,]+)(?::(\\d+))?").matcher(u);
        if (!m.find()) {
            return Optional.empty();
        }
        String kind = m.group(1).toLowerCase();
        int port = m.group(3) != null ? Integer.parseInt(m.group(3)) : DEFAULT_PORTS.getOrDefault(kind, -1);
        return port > 0 ? Optional.of(new Endpoint(kind, m.group(2), port, source)) : Optional.empty();
    }

    static List<Endpoint> parseHostList(String hosts, String kind, String source) {
        List<Endpoint> result = new ArrayList<>();
        for (String part : hosts.split(",")) {
            String p = part.trim().replaceFirst("^[A-Za-z]+://", "");
            if (p.isEmpty()) {
                continue;
            }
            int colon = p.lastIndexOf(':');
            try {
                if (colon > 0) {
                    result.add(new Endpoint(kind, p.substring(0, colon), Integer.parseInt(p.substring(colon + 1)), source));
                } else {
                    result.add(new Endpoint(kind, p, DEFAULT_PORTS.get(kind), source));
                }
            } catch (NumberFormatException e) {
                // ignore malformed entry; the doctor reports what it can parse
            }
        }
        return result;
    }
}
