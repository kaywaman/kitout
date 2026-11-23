package org.kitout.domain.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthEndpointTest {

    private static HealthEndpoint of(String properties, int httpPort) {
        return HealthEndpoint.of(new QuarkusConfig(ConfigFileParser.parseProperties(properties), "dev"), httpPort);
    }

    @Test
    void defaultsToQuarkusDefaults() {
        assertEquals(new HealthEndpoint(8081, "/q/health/ready", false), of("", 8081));
    }

    @Test
    void followsHttpRootPath() {
        assertEquals("/api/q/health/ready", of("quarkus.http.root-path=/api\n", 8080).path());
    }

    @Test
    void absoluteNonApplicationRootIgnoresHttpRoot() {
        assertEquals("/internal/health/ready",
                of("quarkus.http.root-path=/api\nquarkus.http.non-application-root-path=/internal\n", 8080).path());
    }

    @Test
    void followsHealthPaths() {
        assertEquals("/q/status/is-ready",
                of("quarkus.smallrye-health.root-path=status\nquarkus.smallrye-health.readiness-path=is-ready\n", 8080).path());
        assertEquals("/ready", of("quarkus.smallrye-health.readiness-path=/ready\n", 8080).path());
    }

    @Test
    void usesManagementInterfaceWhenEnabled() {
        assertEquals(new HealthEndpoint(9000, "/q/health/ready", false),
                of("quarkus.management.enabled=true\nquarkus.http.root-path=/api\n", 8080));
        assertEquals(new HealthEndpoint(9101, "/manage/health/ready", false),
                of("%dev.quarkus.management.enabled=true\nquarkus.management.port=9101\nquarkus.management.root-path=/manage\n", 8080));
    }

    @Test
    void marksEnvironmentDependentPathsAsAdvisory() {
        assertTrue(of("quarkus.smallrye-health.root-path=${HEALTH_ROOT}\n", 8080).advisory());
    }

    @Test
    void joinsSegmentsLikeQuarkus() {
        assertEquals("/a/b/c", HealthEndpoint.join("/", "a/", "/a/b", "c/"));
        assertEquals("/", HealthEndpoint.join("/", "", null));
    }
}
