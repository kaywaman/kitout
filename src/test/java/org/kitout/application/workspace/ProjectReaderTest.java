package org.kitout.application.workspace;

import org.kitout.application.git.GitCli;
import org.kitout.application.port.CommandRunner.Command;
import org.kitout.domain.project.BuildTool;
import org.kitout.domain.project.Project;
import org.kitout.infrastructure.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reads a realistic project folder: pom.xml, application.properties and a git origin. */
class ProjectReaderTest {

    private final ProcessCommandRunner runner = new ProcessCommandRunner();
    private final ProjectReader reader = new ProjectReader(new GitCli(runner));

    static Path quarkusProject(Path dir, String origin, String properties) throws Exception {
        Files.createDirectories(dir.resolve("src/main/resources"));
        Files.writeString(dir.resolve("pom.xml"), """
                <project><properties><maven.compiler.release>21</maven.compiler.release></properties>
                <dependencies><dependency><groupId>io.quarkus</groupId><artifactId>quarkus-rest</artifactId></dependency></dependencies>
                </project>""");
        Files.writeString(dir.resolve("src/main/resources/application.properties"), properties);
        new ProcessCommandRunner().run(Command.of("git", "init", "-q", dir.toString()));
        new ProcessCommandRunner().run(Command.of("git", "-C", dir.toString(), "remote", "add", "origin", origin));
        return dir;
    }

    @Test
    void readsIdentityPortsAndRoutes() throws Exception {
        Path dir = quarkusProject(Files.createTempDirectory("kitout-reader").resolve("bff"),
                "https://1:tok@gitlab.example.com/acme/shop/bff.git", """
                        %dev.quarkus.http.port=8090
                        %dev.quarkus.rest-client.orders.url=http://localhost:8081
                        %dev.quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/shop
                        """);

        Project p = reader.read(dir).orElseThrow();

        assertEquals("acme.shop.bff", p.id());
        assertEquals("bff", p.name());
        assertEquals(BuildTool.MAVEN, p.buildTool());
        assertEquals(8090, p.devPort());
        assertEquals(Optional.of(21), p.javaRelease());
        assertEquals(Map.of("orders", "http://localhost:8081"), p.restClientUrls());
        assertEquals(1, p.infrastructure().size());
        assertTrue(p.looksLikeBff());
    }

    @Test
    void ignoresNonQuarkusProjects() throws Exception {
        Path dir = Files.createTempDirectory("kitout-reader");
        Files.writeString(dir.resolve("pom.xml"), "<project><artifactId>plain</artifactId></project>");
        assertTrue(reader.read(dir).isEmpty());
    }

    @Test
    void suggestsOneGroupPerNamespaceWithTheBff() throws Exception {
        Path root = Files.createTempDirectory("kitout-groups");
        quarkusProject(root.resolve("bff"), "git@gitlab.example.com:acme/shop/bff.git",
                "%dev.quarkus.rest-client.orders.url=http://localhost:8081\n");
        quarkusProject(root.resolve("orders"), "git@gitlab.example.com:acme/shop/orders.git", "quarkus.http.port=8081\n");
        List<Project> projects = List.of(reader.read(root.resolve("bff")).orElseThrow(),
                reader.read(root.resolve("orders")).orElseThrow());

        var groups = GroupService.suggestions(projects);

        assertEquals(1, groups.size());
        assertEquals("acme-shop", groups.get(0).name());
        assertEquals(List.of("acme.shop.bff"), groups.get(0).bffs());
        assertEquals(List.of(List.of("acme.shop.orders"), List.of("acme.shop.bff")), groups.get(0).startTiers());
    }

    @Test
    void kitoutsOwnSourceIsNeverAService() throws Exception {
        Path dir = quarkusProject(Files.createTempDirectory("kitout-self").resolve("kitout"), "https://gitlab.example.com/acme/tools/kitout.git", "");
        Files.writeString(dir.resolve("pom.xml"), """
                <project><groupId>org.kitout</groupId><artifactId>kitout</artifactId>
                <dependencies><dependency><groupId>io.quarkus</groupId><artifactId>quarkus-rest</artifactId></dependency></dependencies>
                </project>""");
        org.junit.jupiter.api.Assertions.assertTrue(reader.read(dir).isEmpty());
        org.junit.jupiter.api.Assertions.assertFalse(reader.looksLikeService(dir));
    }

    @Test
    void saysWhyARepositoryIsNoService() throws Exception {
        Path infra = Files.createTempDirectory("kitout-infra");
        Files.createDirectories(infra.resolve("compose"));
        Files.writeString(infra.resolve("compose/docker-compose-dev.yml"), "services: {}\n");
        org.junit.jupiter.api.Assertions.assertTrue(reader.whyNoService(infra).orElseThrow().contains("no pom.xml"));
        Files.writeString(infra.resolve("pom.xml"), "<project><artifactId>tools</artifactId></project>");
        org.junit.jupiter.api.Assertions.assertTrue(reader.whyNoService(infra).orElseThrow().contains("neither Quarkus nor Spring Boot"));
        Path service = quarkusProject(Files.createTempDirectory("kitout-svc").resolve("orders"), "https://gitlab.example.com/acme/orders.git", "");
        org.junit.jupiter.api.Assertions.assertTrue(reader.whyNoService(service).isEmpty());
    }
}
