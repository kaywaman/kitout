package org.kitout.application.build;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.git.GitCli;
import org.kitout.application.run.ArtifactLocator;
import org.kitout.application.workspace.ProjectReader;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.domain.build.BuildMode;
import org.kitout.domain.build.BuildRequest;
import org.kitout.domain.build.BuildStatus;
import org.kitout.domain.run.RunMode;
import org.kitout.infrastructure.process.ProcessCommandRunner;
import org.kitout.testsupport.Fakes;
import org.kitout.testsupport.MiniJson;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentBuildsTest {

    @Test
    void theOutputOnDiskIsTheCurrentBuildAndAttemptsAreHistory() throws Exception {
        Path home = Files.createTempDirectory("kitout-current");
        Path orders = Files.createDirectories(home.resolve("work/orders"));
        Files.writeString(orders.resolve("pom.xml"), "<project><version>1.4.0</version><dependencies><dependency><groupId>io.quarkus</groupId>"
                + "</dependency></dependencies></project>");
        Path app = Files.createDirectories(orders.resolve("target/quarkus-app"));
        Files.writeString(app.resolve("quarkus-run.jar"), "jar");
        Files.createDirectories(app.resolve("lib"));
        Files.writeString(app.resolve("lib/a.jar"), "12345");
        Files.createDirectories(orders.resolve("target/maven-archiver"));
        Files.writeString(orders.resolve("target/maven-archiver/pom.properties"), "version=1.3.9\n");

        KitoutPaths paths = new KitoutPaths(Map.of("KITOUT_HOME", home.resolve(".kitout").toString()));
        Fakes.InMemoryConfig config = new Fakes.InMemoryConfig(KitoutConfig.defaults()
                .withService("orders", KitoutConfig.ServiceSettings.defaults(orders.toString())));
        WorkspaceService workspace = new WorkspaceService(new ProjectReader(new GitCli(new ProcessCommandRunner())), config, paths);
        BuildHistory history = new BuildHistory(MiniJson.reader(), paths);
        BuildService builds = new BuildService(workspace, config, null, null, null, paths, history);
        BuildJob older = BuildJob.restored("b1", "orders", new BuildRequest("orders", "dev", BuildMode.JVM, false), null, "1.3.8",
                BuildStatus.SUCCEEDED, Instant.parse("2020-01-01T10:00:00Z"), null, Instant.parse("2020-01-01T10:01:00Z"), null, false, null);
        builds.remember(older);
        CurrentBuilds current = new CurrentBuilds(workspace, new ArtifactLocator(), builds);

        List<CurrentBuilds.Current> list = current.list(id -> Optional.empty());
        assertEquals(1, list.size());
        CurrentBuilds.Current c = list.get(0);
        assertEquals(BuildMode.JVM, c.mode());
        assertEquals(Optional.of("1.3.9"), c.version(), "the version Maven wrote next to the output");
        assertEquals(8L, c.sizeBytes());
        assertEquals(Optional.empty(), c.attemptId(), "b1 finished long before this output was written: built outside kitout");
        assertEquals(Optional.empty(), current.attemptNote(older), "b1 is the newest succeeded attempt and output exists");

        // a running JVM service keeps its output
        var running = current.remove(List.of(new CurrentBuilds.Key("orders", BuildMode.JVM)), id -> Optional.of(RunMode.JVM));
        assertEquals(List.of(), running.removed());
        assertTrue(running.kept().get(0).contains("stop it first"));
        assertTrue(Files.exists(app));

        // stopped: removing deletes the output, and the attempt says so
        var r = current.remove(List.of(new CurrentBuilds.Key("orders", BuildMode.JVM)), id -> Optional.empty());
        assertEquals(List.of("orders (JVM)"), r.removed());
        assertFalse(Files.exists(app));
        assertTrue(Files.exists(orders.resolve("pom.xml")), "only the build output goes");
        assertTrue(current.attemptNote(older).orElseThrow().contains("removed"));

        // an IDE-mode service: its output belongs to the IDE, running or not
        Files.createDirectories(app);
        Files.writeString(app.resolve("quarkus-run.jar"), "jar");
        config.update(cfg -> cfg.withService("orders", cfg.serviceSettings("orders").withRunMode(RunMode.IDE)));
        CurrentBuilds withConfig = new CurrentBuilds(workspace, new ArtifactLocator(), builds, config);
        CurrentBuilds.Current ide = withConfig.list(id -> Optional.empty()).get(0);
        assertFalse(ide.removable());
        assertTrue(ide.usedBy().orElseThrow().contains("IDE mode"));
        assertEquals(List.of(), withConfig.remove(List.of(new CurrentBuilds.Key("orders", BuildMode.JVM)), id -> Optional.empty()).removed());
        assertTrue(Files.exists(app));

        // the attempts survive a restart
        history.save(List.of(older), Map.of());
        BuildService again = new BuildService(workspace, config, null, null, null, paths, history);
        assertEquals(List.of("b1"), again.jobs().stream().map(BuildJob::id).toList());
        assertEquals("1.3.8", again.job("b1").version().orElseThrow());
    }

    @Test
    void attemptsAreKeptTenPerService() throws Exception {
        assertEquals(10, BuildService.KEEP_PER_SERVICE);
    }
}
