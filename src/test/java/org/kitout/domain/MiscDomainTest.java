package org.kitout.domain;

import org.kitout.domain.build.BuildCommandFactory;
import org.kitout.domain.build.BuildMode;
import org.kitout.domain.build.BuildRequest;
import org.kitout.domain.build.NativeBuildErrorExplainer;
import org.kitout.domain.clone.CloneTarget;
import org.kitout.domain.project.BuildTool;
import org.kitout.domain.project.Project;
import org.kitout.domain.identity.RepoIdentity;
import org.kitout.domain.routing.PortMap;
import org.kitout.domain.run.LaunchPlanner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiscDomainTest {

    @Test
    void mavenNativeBuildCommand() {
        BuildRequest r = new BuildRequest("orders", "qa", BuildMode.NATIVE, true);
        assertEquals(List.of("./mvnw", "-B", "clean", "package", "-DskipTests", "-Dquarkus.profile=qa",
                        "-Dquarkus.native.enabled=true", "-Dquarkus.native.container-build=true"),
                BuildCommandFactory.command(BuildTool.MAVEN, r, true));
    }

    @Test
    void gradleJvmBuildCommand() {
        BuildRequest r = new BuildRequest("orders", "dev", BuildMode.JVM, false);
        assertEquals(List.of("./gradlew", "clean", "build", "--console=plain", "-Dquarkus.profile=dev"),
                BuildCommandFactory.command(BuildTool.GRADLE, r, true));
    }

    @Test
    void profileCannotInjectArguments() {
        assertThrows(IllegalArgumentException.class, () -> new BuildRequest("o", "dev -Dx=1", BuildMode.JVM, false));
        assertThrows(IllegalArgumentException.class, () -> new BuildRequest("o", "", BuildMode.JVM, false));
    }

    @Test
    void explainsOutOfMemory() {
        assertTrue(NativeBuildErrorExplainer.explain("Error: Image build request failed with exit code: 137")
                .orElseThrow().cause().contains("memory"));
        assertTrue(NativeBuildErrorExplainer.explain("all good").isEmpty());
    }

    @Test
    void cloneTargetMirrorsNamespace() {
        assertEquals(Path.of("/home/dev/work/acme/shop/orders"),
                CloneTarget.resolve(Path.of("/home/dev/work"), "acme/shop/orders"));
    }

    @Test
    void cloneTargetRejectsEscapes() {
        Path root = Path.of("/home/dev/work");
        for (String bad : List.of("../etc", "acme/../../x", "/abs/path", "acme//x", ".hidden/x", "acme/x.", "a b/c", "")) {
            assertThrows(IllegalArgumentException.class, () -> CloneTarget.resolve(root, bad), bad);
        }
    }

    @Test
    void portMapFindsConflicts() {
        Map<String, Integer> ports = new LinkedHashMap<>();
        ports.put("orders", 8081);
        ports.put("billing", 8081);
        ports.put("bff", 8080);
        assertEquals(Map.of(8081, List.of("orders", "billing")), new PortMap(ports).conflicts());
    }

    @Test
    void imageLaunchUsesHostNetworkAndDevProfile() {
        Project p = new Project("acme.shop.orders", "orders", Path.of("/w/orders"),
                Optional.of(new RepoIdentity("gitlab.com", "acme/shop", "orders")), BuildTool.MAVEN, 8081, false,
                Optional.empty(), List.of(), Map.of(), new org.kitout.domain.config.HealthEndpoint(8081, "/q/health/ready", false));
        var spec = LaunchPlanner.image(p, "registry/acme/shop/orders:mr-1", "dev",
                Map.of("QUARKUS_DATASOURCE_PASSWORD", "s3cret"));
        List<String> cmd = spec.command();
        assertTrue(cmd.containsAll(List.of("--network", "host", "QUARKUS_PROFILE=dev", "kitout-acme.shop.orders")));
        assertEquals("registry/acme/shop/orders:mr-1", cmd.get(cmd.size() - 1));
        assertTrue(cmd.contains("QUARKUS_DATASOURCE_PASSWORD"), "the name is passed with -e");
        assertTrue(cmd.stream().noneMatch(a -> a.contains("s3cret")), "values never appear in the command line");
        assertEquals("s3cret", spec.environment().get("QUARKUS_DATASOURCE_PASSWORD"), "docker reads the value from its environment");
    }

    @Test
    void serviceIdIsUrlSafe() {
        assertEquals("acme.shop.orders-service",
                Project.idFor(Optional.of(new RepoIdentity("h", "Acme/Shop", "orders-service")), Path.of("/x")));
        assertEquals("my.folder", Project.idFor(Optional.empty(), Path.of("/w/My Folder")));
    }
}
