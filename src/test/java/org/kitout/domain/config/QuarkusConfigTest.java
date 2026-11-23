package org.kitout.domain.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuarkusConfigTest {

    private static QuarkusConfig dev(String properties) {
        return new QuarkusConfig(ConfigFileParser.parseProperties(properties), "dev");
    }

    @Test
    void devProfileWins() {
        QuarkusConfig c = dev("quarkus.http.port=8080\n%dev.quarkus.http.port=8081\n");
        assertEquals(8081, EndpointExtractor.httpPort(c));
    }

    @Test
    void otherProfilesAreIgnored() {
        QuarkusConfig c = dev("quarkus.http.port=8082\n%prod.quarkus.http.port=9000\n");
        assertEquals(8082, EndpointExtractor.httpPort(c));
    }

    @Test
    void defaultsTo8080() {
        assertEquals(8080, EndpointExtractor.httpPort(dev("")));
    }

    @Test
    void resolvesPlaceholderDefaults() {
        QuarkusConfig c = dev("quarkus.http.port=${PORT:8085}\n");
        assertEquals(8085, EndpointExtractor.httpPort(c));
        assertFalse(EndpointExtractor.httpPortIsAdvisory(c));
    }

    @Test
    void resolvesReferencesToOtherKeys() {
        QuarkusConfig c = dev("orders.port=8083\n%dev.quarkus.rest-client.orders.url=http://localhost:${orders.port}\n");
        assertEquals(Map.of("orders", "http://localhost:8083"), EndpointExtractor.restClientUrls(c));
    }

    @Test
    void placeholderWithoutDefaultIsUnresolved() {
        QuarkusConfig c = dev("quarkus.http.port=${PORT}\n");
        assertEquals(8080, EndpointExtractor.httpPort(c));
        assertTrue(EndpointExtractor.httpPortIsAdvisory(c));
    }

    @Test
    void survivesPlaceholderCycles() {
        QuarkusConfig c = dev("a=${b}\nb=${a}\n");
        assertEquals(Optional.empty(), c.get("a"));
    }

    @Test
    void readsQuotedRestClientNames() {
        QuarkusConfig c = dev("quarkus.rest-client.\"io.acme.BillingClient\".url=http://localhost:8084\n");
        assertEquals(Map.of("io.acme.BillingClient", "http://localhost:8084"), EndpointExtractor.restClientUrls(c));
    }

    @Test
    void readsInfrastructureFromDevProfile() {
        QuarkusConfig c = dev("""
                %dev.quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5433/orders
                quarkus.datasource.archive.reactive.url=postgresql://db.local/archive
                %dev.quarkus.mongodb.connection-string=mongodb://user:pw@localhost:27018/app
                kafka.bootstrap.servers=localhost:9092,localhost:9093
                """);
        List<Endpoint> endpoints = EndpointExtractor.infrastructure(c);
        assertTrue(endpoints.contains(new Endpoint("postgresql", "localhost", 5433, "quarkus.datasource.jdbc.url")));
        assertTrue(endpoints.contains(new Endpoint("postgresql", "db.local", 5432, "quarkus.datasource.archive.reactive.url")));
        assertTrue(endpoints.contains(new Endpoint("mongodb", "localhost", 27018, "quarkus.mongodb.connection-string")));
        assertTrue(endpoints.contains(new Endpoint("kafka", "localhost", 9093, "kafka.bootstrap.servers")));
        assertEquals(5, endpoints.size());
    }

    @Test
    void devServicesYieldNoInfrastructure() {
        assertTrue(EndpointExtractor.infrastructure(dev("quarkus.datasource.db-kind=postgresql\n")).isEmpty());
    }

    @Test
    void parsesYamlWithProfiles() {
        Map<String, String> raw = ConfigFileParser.parseYaml("""
                quarkus:
                  http:
                    port: 8080   # default
                "%dev":
                  quarkus:
                    http:
                      port: 8086
                    rest-client:
                      orders:
                        url: "http://localhost:8081"
                """);
        QuarkusConfig c = new QuarkusConfig(raw, "dev");
        assertEquals(8086, EndpointExtractor.httpPort(c));
        assertEquals(Map.of("orders", "http://localhost:8081"), EndpointExtractor.restClientUrls(c));
    }
}
