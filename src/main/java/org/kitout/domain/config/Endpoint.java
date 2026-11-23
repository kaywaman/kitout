package org.kitout.domain.config;

/**
 * A host and port a service depends on (database, broker) or routes to (BFF target).
 *
 * @param kind   what it is, e.g. {@code postgresql}, {@code mongodb}, {@code kafka}, {@code http}
 * @param source the config key it came from, for messages the developer can act on
 */
public record Endpoint(String kind, String host, int port, String source) {

    public boolean isLocal() {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("0.0.0.0");
    }

    public String address() {
        return host + ":" + port;
    }
}
