package org.kitout.application.team;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.git.GitCli;
import org.kitout.application.port.CommandRunner.Command;
import org.kitout.infrastructure.process.ProcessCommandRunner;
import org.kitout.testsupport.Fakes;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepositoryLocatorTest {

    @Test
    void findsARepositoryClonedByHandSomewhereElse() throws Exception {
        Path home = Files.createTempDirectory("kitout-locate").toRealPath();
        Path byHand = Files.createDirectories(home.resolve("projects/infrastructure"));
        new ProcessCommandRunner().run(Command.of("git", "init", "-q", byHand.toString()));
        new ProcessCommandRunner().run(Command.of("git", "-C", byHand.toString(), "remote", "add", "origin",
                "https://gitlab.example.com/acme/platform/infrastructure.git"));
        Path decoy = Files.createDirectories(home.resolve("projects/other/infrastructure"));
        new ProcessCommandRunner().run(Command.of("git", "init", "-q", decoy.toString()));
        Fakes.InMemoryConfig config = new Fakes.InMemoryConfig(KitoutConfig.defaults()
                .withWorkspaceRoots(List.of(home.resolve("projects").toString()))
                .withGitlab(new KitoutConfig.GitLabSettings("gitlab.example.com", "https", home.resolve("work").toString(), "", "{project}")));
        RepositoryLocator locator = new RepositoryLocator(config, new KitoutPaths(Map.of("KITOUT_HOME", home.resolve(".kitout").toString())),
                new GitCli(new ProcessCommandRunner()));

        assertEquals(Optional.of(byHand), locator.locate("acme/platform/infrastructure"), "found by its git origin, not by its name");
        assertEquals(home.resolve("work/acme/platform/infrastructure"), locator.cloneTarget("acme/platform/infrastructure"));
        assertEquals(Optional.empty(), locator.locate("acme/platform/missing"));
    }
}
