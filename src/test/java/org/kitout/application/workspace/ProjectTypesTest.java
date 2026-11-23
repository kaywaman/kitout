package org.kitout.application.workspace;

import org.kitout.application.git.GitCli;
import org.kitout.application.port.CommandRunner.Command;
import org.kitout.domain.project.BuildTool;
import org.kitout.domain.project.Project;
import org.kitout.domain.project.ProjectType;
import org.kitout.infrastructure.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectTypesTest {

    private final ProjectReader reader = new ProjectReader(new GitCli(new ProcessCommandRunner()));

    private static Path repo(String name, String origin) throws Exception {
        Path dir = Files.createTempDirectory("kitout-types").resolve(name);
        Files.createDirectories(dir.resolve("src/main/resources"));
        new ProcessCommandRunner().run(Command.of("git", "init", "-q", dir.toString()));
        new ProcessCommandRunner().run(Command.of("git", "-C", dir.toString(), "remote", "add", "origin", origin));
        return dir;
    }

    @Test
    void springBootReadsItsProfileFileAndActuator() throws Exception {
        Path dir = repo("payments", "https://gitlab.example.com/acme/shop/payments.git");
        Files.writeString(dir.resolve("pom.xml"), """
                <project><parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId></parent>
                <properties><java.version>21</java.version><maven.compiler.release>21</maven.compiler.release></properties></project>""");
        Files.writeString(dir.resolve("src/main/resources/application.properties"), """
                server.port=8081
                management.endpoint.health.probes.enabled=true
                spring.data.mongodb.uri=mongodb://localhost:27017/payments
                """);
        Files.writeString(dir.resolve("src/main/resources/application-dev.yml"), "server:\n  port: 8085\n");

        Project p = reader.read(dir).orElseThrow();

        assertEquals(ProjectType.SPRING_BOOT, p.type());
        assertEquals("acme.shop.payments", p.id());
        assertEquals(8085, p.devPort(), "the dev profile's file wins");
        assertEquals("/actuator/health/readiness", p.health().path());
        assertEquals(8085, p.health().port());
        assertEquals("mongodb", p.infrastructure().get(0).kind());
        assertTrue(reader.looksLikeService(dir));
    }

    @Test
    void springBootWithoutProbesOnASeparateManagementPort() throws Exception {
        Path dir = repo("search", "https://gitlab.example.com/acme/shop/search.git");
        Files.writeString(dir.resolve("build.gradle.kts"), "plugins { id(\"org.springframework.boot\") version \"3.5.0\" }");
        Files.writeString(dir.resolve("src/main/resources/application.properties"), """
                server.servlet.context-path=/search
                management.server.port=9091
                """);
        Project p = reader.read(dir).orElseThrow();
        assertEquals(BuildTool.GRADLE, p.buildTool());
        assertEquals(8080, p.devPort());
        assertEquals(9091, p.health().port());
        assertEquals("/actuator/health", p.health().path(), "no context path on a separate management port; no probes: /health");
    }

    @Test
    void plainJavaNeedsAMainClassAndHasNoPortUntilOneIsSet() throws Exception {
        Path app = repo("importer", "https://gitlab.example.com/acme/tools/importer.git");
        Files.writeString(app.resolve("pom.xml"), """
                <project><build><plugins><plugin><artifactId>maven-shade-plugin</artifactId><configuration><transformers>
                <transformer><mainClass>com.acme.Importer</mainClass></transformer></transformers></configuration></plugin></plugins></build></project>""");
        Project p = reader.read(app).orElseThrow();
        assertEquals(ProjectType.JAVA, p.type());
        assertEquals(0, p.devPort());
        assertTrue(!p.hasHealthEndpoint());
        assertEquals(9000, p.withPort(9000).devPort());

        Path library = repo("java-commons", "https://gitlab.example.com/acme/common/java-commons.git");
        Files.writeString(library.resolve("pom.xml"), "<project><artifactId>java-commons</artifactId></project>");
        assertTrue(reader.read(library).isEmpty(), "a library is never a service");
        assertTrue(reader.whyNoService(library).orElseThrow().contains("a library?"));
    }

    @Test
    void nodeFrontendsHaveStartScriptsAndAGuessedPort() throws Exception {
        Path web = repo("web", "https://gitlab.example.com/acme/shop/web.git");
        Files.writeString(web.resolve("package.json"), """
                { "name": "@acme/web", "version": "2.1.0", "private": true,
                  "scripts": { "build": "vite build", "dev": "vite --port 4000", "start": "vite preview" } }""");
        Project p = reader.read(web).orElseThrow();
        assertEquals(ProjectType.NODE, p.type());
        assertEquals(BuildTool.NPM, p.buildTool());
        assertEquals(List.of("dev", "start"), p.scripts());
        assertEquals(4000, p.devPort());
        assertTrue(p.devPortAdvisory(), "a guess: shown with a question mark");
        assertEquals("2.1.0", p.version().orElseThrow());

        Path tooling = repo("eslint-config", "https://gitlab.example.com/acme/common/eslint-config.git");
        Files.writeString(tooling.resolve("package.json"), "{ \"name\": \"eslint-config\", \"scripts\": { \"test\": \"node --test\" } }");
        assertTrue(reader.read(tooling).isEmpty(), "nothing to start: not a service");
    }

    @Test
    void quarkusStaysFirstEvenWithSpringCompatibility() throws Exception {
        Path dir = repo("orders", "https://gitlab.example.com/acme/shop/orders.git");
        Files.writeString(dir.resolve("pom.xml"), """
                <project><dependencies><dependency><groupId>io.quarkus</groupId><artifactId>quarkus-spring-web</artifactId></dependency>
                </dependencies></project>""");
        assertEquals(ProjectType.QUARKUS, reader.read(dir).orElseThrow().type());
    }
}
