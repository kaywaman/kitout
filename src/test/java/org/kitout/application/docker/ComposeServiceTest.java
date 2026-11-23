package org.kitout.application.docker;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.workspace.FolderBrowser;
import org.kitout.domain.docker.ComposeFile;
import org.kitout.testsupport.Fakes;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposeServiceTest {

    @Test
    void findsEnvironmentsInTheConfiguredFolders() throws Exception {
        Path tmp = Files.createTempDirectory("kitout-compose");
        Path infra = Files.createDirectories(tmp.resolve("infra"));
        Files.writeString(infra.resolve("docker-compose-dev.yml"), "services: {}\n");
        Files.writeString(infra.resolve("docker-compose-test.yml"), "services: {}\n");
        Files.writeString(infra.resolve("docker-compose.override.yml"), "services: {}\n");
        Files.writeString(infra.resolve("notes.txt"), "");
        Files.createDirectories(infra.resolve("nested")).resolve("docker-compose-deep.yml").toFile().createNewFile();
        Fakes.InMemoryConfig config = new Fakes.InMemoryConfig(KitoutConfig.defaults()
                .withDocker(new KitoutConfig.DockerSettings(List.of(infra.toString(), tmp.resolve("missing").toString()), List.of())));
        ComposeService compose = new ComposeService(config, new KitoutPaths(Map.of("KITOUT_HOME", tmp.resolve("home").toString())),
                null, null, null, null, org.kitout.application.team.TeamDefaults.NONE);

        assertEquals(List.of("dev", "test"), compose.environments().stream().map(ComposeService.Environment::id).toList(),
                "subfolders and override files are not environments");
        assertEquals(Map.of(infra.toString(), true, tmp.resolve("missing").toString(), false), compose.folders());
        assertThrows(NoSuchElementException.class, () -> compose.require("prod"));
    }

    @Test
    void sameEnvironmentInTwoFoldersGetsDistinctIds() {
        List<ComposeService.Environment> envs = ComposeService.withUniqueIds(List.of(
                new ComposeFile("dev", Path.of("/w/infra/docker-compose-dev.yml")),
                new ComposeFile("dev", Path.of("/w/kafka/docker-compose-dev.yml")),
                new ComposeFile("test", Path.of("/w/infra/docker-compose-test.yml"))), Set.of());
        assertEquals(List.of("dev@infra", "dev@kafka", "test"), envs.stream().map(ComposeService.Environment::id).toList());
    }

    @Test
    void developersPickAnyComposeFileInTheirHome() throws Exception {
        Path home = Files.createTempDirectory("kitout-home").toRealPath();
        Path mine = Files.createDirectories(home.resolve("work/my-setup"));
        Files.writeString(mine.resolve("kafka-debug.yml"), "# my own stack\nservices:\n  kafka:\n    image: apache/kafka\n");
        Files.writeString(mine.resolve("application.yaml"), "quarkus:\n  http:\n    port: 8081\n");
        Fakes.InMemoryConfig config = new Fakes.InMemoryConfig(KitoutConfig.defaults());
        FolderBrowser browser = new FolderBrowser(new KitoutPaths(Map.of("KITOUT_HOME", home.resolve(".kitout").toString())) {
            @Override
            public Path home() {
                return home;
            }
        }, null);
        ComposeService compose = new ComposeService(config, new KitoutPaths(Map.of("KITOUT_HOME", home.resolve(".kitout").toString())),
                null, null, null, browser, org.kitout.application.team.TeamDefaults.NONE);

        List<ComposeService.Candidate> candidates = compose.candidates(mine.toString());
        assertEquals(List.of("application.yaml", "kafka-debug.yml"), candidates.stream().map(ComposeService.Candidate::name).toList());
        assertEquals(List.of(false, true), candidates.stream().map(ComposeService.Candidate::compose).toList(),
                "only files with a top-level services: can be picked");

        ComposeService.Environment env = compose.pick(mine.resolve("kafka-debug.yml").toString());
        assertEquals("kafka-debug", env.id());
        assertEquals(true, env.picked());
        assertEquals(List.of("kafka-debug"), compose.environments().stream().map(ComposeService.Environment::id).toList());

        assertThrows(IllegalArgumentException.class, () -> compose.pick(mine.resolve("application.yaml").toString()));
        Path outside = Files.createTempFile("kitout-outside", ".yml");
        Files.writeString(outside, "services: {}\n");
        assertThrows(IllegalArgumentException.class, () -> compose.pick(outside.toString()), "only inside home");

        compose.forget(mine.resolve("kafka-debug.yml").toString());
        assertEquals(List.of(), compose.environments());
        assertEquals(true, Files.exists(mine.resolve("kafka-debug.yml")), "forgetting keeps the file");
    }

    @Test
    void commandsAreArgumentLists() {
        Path file = Path.of("/w/infra/docker-compose-dev.yml");
        assertEquals(List.of("docker", "compose", "-f", file.toString(), "up", "-d"), ComposeService.command(file, ComposeJob.Action.UP));
        assertEquals(List.of("docker", "compose", "-f", file.toString(), "down"), ComposeService.command(file, ComposeJob.Action.DOWN));
    }

    @Test
    void theTeamsEnvironmentsResolveUnderTheWorkspaceRoot() throws Exception {
        Path work = Files.createTempDirectory("kitout-work");
        org.kitout.domain.team.TeamSetup team = org.kitout.testsupport.TeamFixtures.filled();
        Fakes.InMemoryConfig config = new Fakes.InMemoryConfig(KitoutConfig.defaults().withGitlab(
                new KitoutConfig.GitLabSettings("gitlab.example.com", "https", work.toString(), "", "{project}")));
        ComposeService compose = new ComposeService(config, new KitoutPaths(Map.of("KITOUT_HOME", work.resolve(".kitout").toString())),
                null, null, null, null, () -> java.util.Optional.of(team));

        ComposeService.Environment first = compose.environments().get(0);
        assertEquals("dev", first.id());
        assertEquals(true, first.team());
        assertTrue(first.missing().get(0).startsWith("acme/platform/infrastructure is not cloned"), first.missing().toString());
        assertTrue(first.autoStart(), "team environments start by themselves unless the team file says otherwise");
        assertThrows(IllegalStateException.class, () -> compose.start("dev", org.kitout.application.docker.ComposeJob.Action.UP));
        IllegalStateException e = assertThrows(IllegalStateException.class, compose::ensureTeamEnvironments,
                "a service start says why the databases cannot come up");
        assertTrue(e.getMessage().contains("not cloned"), e.getMessage());

        Path file = work.resolve("acme/platform/infrastructure/compose/docker-compose-dev.yml");
        Files.createDirectories(file.getParent());
        assertTrue(compose.environments().get(0).missing().get(0).contains("check \"path\""), "repository there, file not: say so");
        Files.writeString(file, "services: {}\n");
        assertEquals(List.of(), compose.environments().get(0).missing());
        assertEquals(file, compose.environments().get(0).file());
    }

    @Test
    void overridesAndProfilesGoToDockerCompose() {
        ComposeService.Environment env = new ComposeService.Environment("dev", "dev", Path.of("/w/infra/docker-compose-dev.yml"), false,
                List.of(Path.of("/w/infra/my-override.yml")), List.of("kafka"), true, null, List.of());
        assertEquals(List.of("docker", "compose", "-f", "/w/infra/docker-compose-dev.yml", "-f", "/w/infra/my-override.yml",
                "--profile", "kafka", "up", "-d"), ComposeService.command(env, org.kitout.application.docker.ComposeJob.Action.UP));
    }
}
