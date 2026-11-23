package org.kitout.domain.project;

import org.kitout.domain.build.BuildCommandFactory;
import org.kitout.domain.build.BuildMode;
import org.kitout.domain.build.BuildRequest;
import org.kitout.domain.config.HealthEndpoint;
import org.kitout.domain.config.NodePackage;
import org.kitout.domain.run.LaunchPlanner;
import org.kitout.domain.run.LaunchSpec;
import org.kitout.domain.run.RunMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectTypeLaunchTest {

    static Project project(ProjectType type, BuildTool tool) {
        return new Project("acme.x", "x", Path.of("/w/x"), Optional.empty(), tool, 8080, false, Optional.empty(), List.of(), Map.of(),
                new HealthEndpoint(8080, "/actuator/health", false), Optional.empty(), type, List.of("dev"), Optional.empty());
    }

    @Test
    void eachTypeGetsItsProfileTheWayItReadsIt() {
        LaunchSpec spring = LaunchPlanner.jvm(project(ProjectType.SPRING_BOOT, BuildTool.MAVEN), Path.of("/w/x/target/x.jar"), "qa", List.of(), Map.of());
        assertEquals(List.of("java", "-Dspring.profiles.active=qa", "-jar", "/w/x/target/x.jar"), spring.command());
        LaunchSpec java = LaunchPlanner.jvm(project(ProjectType.JAVA, BuildTool.MAVEN), Path.of("/w/x/target/x.jar"), "qa", List.of("-Xmx1g"), Map.of());
        assertEquals(List.of("java", "-Xmx1g", "-jar", "/w/x/target/x.jar"), java.command(), "a plain Java app: no profile");
        List<String> image = LaunchPlanner.image(project(ProjectType.SPRING_BOOT, BuildTool.MAVEN), "r/x:1", "qa", Map.of()).command();
        assertTrue(image.contains("SPRING_PROFILES_ACTIVE=qa"), String.join(" ", image));
        assertFalse(image.stream().anyMatch(a -> a.startsWith("QUARKUS_PROFILE")));
        List<String> node = LaunchPlanner.image(project(ProjectType.NODE, BuildTool.NPM), "r/x:1", "dev", Map.of()).command();
        assertFalse(node.stream().anyMatch(a -> a.contains("PROFILE")), String.join(" ", node));
    }

    @Test
    void nodeRunsItsScriptWithThePortInPort() {
        LaunchSpec s = LaunchPlanner.script(project(ProjectType.NODE, BuildTool.NPM), "dev", 4000, Map.of("API", "http://localhost:8080"));
        assertEquals(List.of("npm", "run", "dev"), s.command());
        assertEquals("4000", s.environment().get("PORT"));
        assertEquals(Path.of("/w/x"), s.workingDirectory());
    }

    @Test
    void onlyQuarkusTakesTheBuildProfileAndNativeBuilds() {
        BuildRequest qa = new BuildRequest("acme.x", "qa", BuildMode.JVM, true);
        assertEquals(List.of("./mvnw", "-B", "clean", "package", "-DskipTests"),
                BuildCommandFactory.command(ProjectType.SPRING_BOOT, BuildTool.MAVEN, qa, true));
        assertEquals(List.of("./gradlew", "clean", "build", "--console=plain"),
                BuildCommandFactory.command(ProjectType.JAVA, BuildTool.GRADLE, new BuildRequest("acme.x", "dev", BuildMode.JVM, false), true));
        assertTrue(BuildCommandFactory.command(ProjectType.QUARKUS, BuildTool.MAVEN, qa, true).contains("-Dquarkus.profile=qa"));
        assertThrows(IllegalArgumentException.class, () -> BuildCommandFactory.command(ProjectType.SPRING_BOOT, BuildTool.MAVEN,
                new BuildRequest("acme.x", "dev", BuildMode.NATIVE, true), true));
        assertThrows(IllegalArgumentException.class, () -> BuildCommandFactory.command(ProjectType.NODE, BuildTool.NPM, qa, true));
    }

    @Test
    void aModeATypeLacksBecomesItsOwn() {
        assertEquals(RunMode.SCRIPT, ProjectType.NODE.defaultMode(RunMode.JVM), "the team's local mode, for Node: its npm script");
        assertEquals(RunMode.IMAGE, ProjectType.NODE.defaultMode(RunMode.IMAGE));
        assertEquals(RunMode.JVM, ProjectType.SPRING_BOOT.defaultMode(RunMode.NATIVE));
        assertEquals(RunMode.IMAGE, ProjectType.IMAGE.defaultMode(RunMode.JVM));
        assertEquals(ProjectType.SPRING_BOOT, ProjectType.ofId("spring-boot"));
        assertThrows(IllegalArgumentException.class, () -> ProjectType.ofId("python"));
    }

    @Test
    void nodePortsComeFromTheScriptTheConfigOrTheTool() {
        assertEquals(4000, NodePackage.port("vite --port 4000", "", ""));
        assertEquals(5174, NodePackage.port("vite", "export default { server: { port: 5174 } }", ""));
        assertEquals(5173, NodePackage.port("vite", "", ""));
        assertEquals(4300, NodePackage.port("ng serve", "", "{ \"serve\": { \"options\": { \"port\": 4300 } } }"));
        assertEquals(3001, NodePackage.port("next dev -p 3001", "", ""));
        assertEquals(3000, NodePackage.port("node server.js", "", ""));
        assertEquals(Map.of("dev", "vite", "lint", "eslint \"src\""), NodePackage.scripts("{\"scripts\": {\"dev\": \"vite\", \"lint\": \"eslint \\\"src\\\"\"}}"));
        assertEquals("web", NodePackage.name("{ \"name\": \"@acme/web\" }").orElseThrow());
    }
}
