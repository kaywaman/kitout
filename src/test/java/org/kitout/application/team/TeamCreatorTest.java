package org.kitout.application.team;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.env.EnvironmentService;
import org.kitout.application.git.GitCli;
import org.kitout.application.port.CommandRunner;
import org.kitout.application.port.CommandRunner.Command;
import org.kitout.application.workspace.ProjectReader;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.domain.team.TeamSetup;
import org.kitout.infrastructure.process.ProcessCommandRunner;
import org.kitout.testsupport.Fakes;
import org.kitout.testsupport.MiniJson;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamCreatorTest {

    private final ProcessCommandRunner git = new ProcessCommandRunner();

    @Test
    void aSingleDeveloperBuildsATeamFromAnInfrastructureComposeFile() throws Exception {
        Path home = Files.createTempDirectory("kitout-scratch").toRealPath();
        KitoutPaths paths = new KitoutPaths(Map.of("KITOUT_HOME", home.resolve(".kitout").toString(), "HOME", home.toString())) {
            @Override
            public Path home() {
                return home;
            }
        };
        // the infrastructure: a compose file and a .env, in a repository with an origin
        Path infra = Files.createDirectories(home.resolve("work/acme/platform/infrastructure"));
        git.run(Command.of("git", "init", "-q", infra.toString()));
        git.run(Command.of("git", "-C", infra.toString(), "remote", "add", "origin", "https://gitlab.example.com/acme/platform/infrastructure.git"));
        Files.createDirectories(infra.resolve("compose"));
        Files.writeString(infra.resolve("compose/docker-compose-dev.yml"), "services:\n  mongo:\n    image: mongo:8\n");
        Files.writeString(infra.resolve("compose/.env"), "MONGO_HOST=localhost\nMONGO_PORT=27017\nMONGO_USERNAME=dev\nMONGO_PASSWORD=s3cret\n");
        // a scratch file outside any repository
        Files.createDirectories(home.resolve("tools"));
        Files.writeString(home.resolve("tools/docker-compose-tools.yml"), "services:\n  mailpit:\n    image: axllent/mailpit\n");

        Fakes.InMemoryConfig config = new Fakes.InMemoryConfig(KitoutConfig.defaults());
        Fakes.InMemorySecrets secrets = new Fakes.InMemorySecrets();
        TeamService team = new TeamService(config, null, null, MiniJson.reader(), paths, "1.0.0", Optional.empty());
        ProjectReader reader = new ProjectReader(new GitCli(git));
        WorkspaceService workspace = new WorkspaceService(reader, config, paths, team);
        EnvironmentService environment = new EnvironmentService(config, secrets, workspace, team);
        CommandRunner noTools = c -> new CommandRunner.Result(-1, "");
        TeamCreator creator = new TeamCreator(team, new MachineProbe(noTools, Map.of()), MiniJson.reader(), paths, new GitCli(git), config,
                environment, workspace, "1.0.0", false);

        TeamService.Status status = creator.create(new TeamCreator.Draft("acme-shop", "gitlab.example.com", "~/work", "", "",
                List.of("~/work/acme/platform/infrastructure/compose/docker-compose-dev.yml", "~/tools/docker-compose-tools.yml"),
                "~/work/acme/platform/infrastructure/compose/.env", true));

        TeamSetup t = status.setup().orElseThrow(() -> new AssertionError(status.problems()));
        assertEquals("acme-shop", t.team());
        assertEquals(List.of(), t.services(), "no services yet: they come later");
        assertEquals("JVM", t.serviceDefaults().runMode(), "no CI/CD images: services run from local builds");
        TeamSetup.ComposeEnvironment dev = t.compose().get(0);
        assertEquals("acme/platform/infrastructure", dev.files().get(0).repo(), "inside a repository: named by repo and path");
        assertEquals("compose/docker-compose-dev.yml", dev.files().get(0).path());
        assertEquals("~/tools/docker-compose-tools.yml", dev.files().get(1).file(), "outside any repository: a local file");
        assertTrue(dev.autoStart());
        assertEquals(List.of("acme/platform/infrastructure"), t.nonServiceRepositories().stream().map(TeamSetup.Repository::repo).toList());
        assertEquals(Map.of("MONGO_HOST", "localhost", "MONGO_PORT", "27017", "MONGO_USERNAME", "dev"), t.variables());
        assertEquals(List.of("MONGO_PASSWORD"), t.secrets(), "the password is a secret name in the file...");
        assertEquals(List.of(), environment.missingTeamSecrets(), "...and its value is in the keyring");
        assertTrue(!Files.readString(creator.file()).contains("s3cret"), "never in the file");
        assertTrue(creator.editable());
        assertTrue(creator.export().contains("\"team\": \"acme-shop\""));

        // later: the first service
        Path orders = Files.createDirectories(home.resolve("work/acme/shop/orders"));
        git.run(Command.of("git", "init", "-q", orders.toString()));
        git.run(Command.of("git", "-C", orders.toString(), "remote", "add", "origin", "https://gitlab.example.com/acme/shop/orders.git"));
        Files.writeString(orders.resolve("pom.xml"), "<project><dependencies><dependency><groupId>io.quarkus</groupId></dependency></dependencies></project>");
        assertEquals(List.of("acme/shop/orders"), creator.addServices(List.of("~/work/acme/shop/orders")));
        TeamSetup after = team.status().setup().orElseThrow();
        assertEquals(List.of("acme/shop/orders"), after.services().stream().map(TeamSetup.Service::repo).toList());
        assertEquals(List.of("acme/shop/orders"), after.groups().get(0).members());

        Path fork = Files.createDirectories(home.resolve("work/fork/orders"));
        git.run(Command.of("git", "init", "-q", fork.toString()));
        git.run(Command.of("git", "-C", fork.toString(), "remote", "add", "origin", "https://github.com/someone/orders.git"));
        Files.writeString(fork.resolve("pom.xml"), "<project><dependencies><dependency><groupId>io.quarkus</groupId></dependency></dependencies></project>");
        assertThrows(IllegalArgumentException.class, () -> creator.addServices(List.of("~/work/fork/orders")), "another host");
    }

    @Test
    void aTeamThatRequiresItsOwnFileCannotBuildOneHere() throws Exception {
        Path home = Files.createTempDirectory("kitout-required");
        KitoutPaths paths = new KitoutPaths(Map.of("KITOUT_HOME", home.toString()));
        TeamCreator creator = new TeamCreator(null, null, MiniJson.reader(), paths, null, null, null, null, "1.0.0", true);
        assertThrows(IllegalStateException.class, () -> creator.create(new TeamCreator.Draft("x", "", "", "", "", List.of(), "", false)));
    }
}
