package org.kitout.application.workspace;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.git.GitCli;
import org.kitout.application.run.ServiceStatus;
import org.kitout.domain.group.Group;
import org.kitout.domain.run.RunMode;
import org.kitout.domain.run.ServiceHealth;
import org.kitout.domain.run.ServiceState;
import org.kitout.infrastructure.process.ProcessCommandRunner;
import org.kitout.testsupport.Fakes;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hidden projects, group clean-up on removal, and the folder browser's limits. */
class WorkspaceAdminTest {

    private final ProjectReader reader = new ProjectReader(new GitCli(new ProcessCommandRunner()));

    @Test
    void scanSkipsHiddenProjectsAndPreviewNamesThem() throws Exception {
        Path root = Files.createTempDirectory("kitout-ws");
        Path orders = ProjectReaderTest.quarkusProject(root.resolve("orders"), "git@gitlab.example.com:acme/orders.git", "");
        Path lib = ProjectReaderTest.quarkusProject(root.resolve("common-lib"), "git@gitlab.example.com:acme/common-lib.git", "");
        Fakes.InMemoryConfig config = new Fakes.InMemoryConfig(KitoutConfig.defaults()
                .withHiddenProjects(List.of(lib.toAbsolutePath().normalize().toString())));
        WorkspaceService workspace = new WorkspaceService(reader, config, new KitoutPaths(Map.of("KITOUT_HOME", root.resolve("home").toString())));

        WorkspaceService.ScanResult scan = workspace.scan(List.of(root.toString()));

        assertEquals(List.of("acme.orders"), scan.found().stream().map(p -> p.id()).toList());
        WorkspaceService.Preview preview = workspace.preview(root.toString());
        assertEquals(1, preview.projects().size());
        assertEquals(List.of(lib.toAbsolutePath().normalize().toString()), preview.hiddenPaths());
        assertTrue(config.load().services().containsKey("acme.orders"));
        assertFalse(config.load().services().containsKey("acme.common-lib"));
        assertTrue(Files.isDirectory(orders), "nothing on disk changes");
    }

    @Test
    void withATeamFileOnlyItsServicesAreLoaded() throws Exception {
        Path root = Files.createTempDirectory("kitout-team-ws");
        ProjectReaderTest.quarkusProject(root.resolve("acme/shop/orders"), "https://gitlab.example.com/acme/shop/orders.git", "");
        Path mine = ProjectReaderTest.quarkusProject(root.resolve("playground"), "https://gitlab.example.com/me/playground.git", "");
        Path foreign = ProjectReaderTest.quarkusProject(root.resolve("fork"), "https://github.com/acme/shop/orders.git", "");
        org.kitout.domain.team.TeamSetup team = org.kitout.testsupport.TeamFixtures.filled();
        Fakes.InMemoryConfig config = new Fakes.InMemoryConfig(KitoutConfig.defaults());
        WorkspaceService workspace = new WorkspaceService(reader, config, new KitoutPaths(Map.of("KITOUT_HOME", root.resolve("home").toString())),
                () -> java.util.Optional.of(team));

        WorkspaceService.ScanResult scan = workspace.scan(List.of(root.toString()));

        assertEquals(List.of("acme.shop.orders"), scan.found().stream().map(p -> p.id()).toList());
        assertEquals(java.util.Set.of(mine.toAbsolutePath().normalize().toString(), foreign.toAbsolutePath().normalize().toString()),
                java.util.Set.copyOf(scan.outsideTeam()), "another project, and the same path on another host, are left out");
        assertEquals(1, workspace.preview(root.toString()).projects().size());
    }

    @Test
    void removingAMemberCleansUpGroups() {
        List<Group> groups = List.of(
                new Group("shop", List.of("orders", "bff"), List.of("bff"), false, false),
                new Group("solo", List.of("bff"), List.of("bff"), false, false));

        List<Group> result = ServiceAdminService.withoutMembers(groups, Set.of("bff"));

        assertEquals(1, result.size(), "a group with no members left is dropped");
        assertEquals(List.of("orders"), result.get(0).members());
        assertEquals(List.of(), result.get(0).bffs(), "a removed BFF is no longer an entry point");
    }

    @Test
    void aServiceKitoutRunsCannotBeRemovedUntilItIsStopped() {
        for (ServiceState state : ServiceState.values()) {
            assertEquals(state != ServiceState.STOPPED, ServiceAdminService.blocksRemoval(status(state, RunMode.JVM)),
                    state + " in JVM mode");
            assertFalse(ServiceAdminService.blocksRemoval(status(state, RunMode.IDE)),
                    "kitout does not run IDE services, so they never block (" + state + ")");
        }
    }

    private static ServiceStatus status(ServiceState state, RunMode mode) {
        return new ServiceStatus("acme.orders", "orders", state, mode, 8081, "", Optional.empty(),
                ServiceHealth.UNKNOWN, Optional.empty());
    }

    @Test
    void folderBrowserListsFoldersAndMarksProjects() throws Exception {
        Path home = Files.createTempDirectory("kitout-home");
        ProjectReaderTest.quarkusProject(home.resolve("work/orders"), "git@gitlab.example.com:acme/orders.git", "");
        Files.createDirectories(home.resolve("work/notes"));
        Files.createDirectories(home.resolve("work/.cache"));
        FolderBrowser browser = new FolderBrowser(home, reader);

        FolderBrowser.Listing listing = browser.list("~/work");

        assertEquals(List.of("notes", "orders"), listing.entries().stream().map(FolderBrowser.Entry::name).toList());
        assertTrue(listing.entries().get(1).quarkusProject());
        assertTrue(listing.entries().get(1).gitRepository());
        assertEquals(home.toRealPath().toString(), listing.parent());
        assertEquals(null, browser.list("").parent(), "home is as far up as it goes");
    }

    @Test
    void folderBrowserStaysInsideHome() throws Exception {
        Path home = Files.createTempDirectory("kitout-home");
        Path outside = Files.createTempDirectory("kitout-outside");
        Files.createSymbolicLink(home.resolve("escape"), outside);
        FolderBrowser browser = new FolderBrowser(home, reader);

        assertThrows(IllegalArgumentException.class, () -> browser.list("/etc"));
        assertThrows(IllegalArgumentException.class, () -> browser.list(home + "/.."));
        assertThrows(IllegalArgumentException.class, () -> browser.list("~/escape"), "symlinks out of home are refused");
        assertThrows(NoSuchElementException.class, () -> browser.list("~/does-not-exist"));
    }
}
