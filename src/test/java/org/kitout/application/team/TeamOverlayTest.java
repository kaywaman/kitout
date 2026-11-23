package org.kitout.application.team;

import org.kitout.application.config.KitoutConfig;
import org.kitout.application.config.KitoutConfig.TeamSettings;
import org.kitout.domain.run.RunMode;
import org.kitout.domain.team.TeamSetup;
import org.kitout.testsupport.TeamFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamOverlayTest {

    private final TeamSetup team = TeamFixtures.filled();
    private final String orders = TeamOverlay.serviceId("acme/shop/orders");

    private KitoutConfig personal() {
        return KitoutConfig.defaults().withService(orders, KitoutConfig.ServiceSettings.defaults("/home/dev/work/acme/shop/orders"));
    }

    @Test
    void theTeamFillsWhatTheDeveloperDidNotChange() {
        KitoutConfig shown = TeamOverlay.apply(personal(), team, LocalDate.of(2026, 10, 30));
        assertEquals("gitlab.example.com", shown.gitlab().host());
        assertEquals("images.example.com", shown.gitlab().registryHost());
        assertEquals(RunMode.IMAGE, shown.serviceSettings(orders).runMode());
        assertEquals(180, shown.serviceSettings(orders).startTimeoutSeconds());
        assertTrue(shown.groups().stream().anyMatch(g -> g.name().equals("shop") && g.suggested()));
    }

    @Test
    void theDevelopersOwnChoiceWins() {
        KitoutConfig mine = personal().withService(orders, personal().serviceSettings(orders).withRunMode(RunMode.IDE));
        KitoutConfig shown = TeamOverlay.apply(mine, team, LocalDate.of(2026, 10, 30));
        assertEquals(RunMode.IDE, shown.serviceSettings(orders).runMode());
    }

    @Test
    void savingNeverCopiesTeamValuesIntoTheDevelopersFile() {
        KitoutConfig before = personal();
        KitoutConfig shown = TeamOverlay.apply(before, team, LocalDate.of(2026, 10, 30));
        KitoutConfig after = shown.withHiddenProjects(java.util.List.of("/home/dev/work/old"));   // an unrelated change
        KitoutConfig saved = TeamOverlay.unapply(before, shown, after);
        assertEquals("", saved.gitlab().host(), "the team's host stays the team's");
        assertEquals(before.serviceSettings(orders), saved.serviceSettings(orders));
        assertTrue(saved.groups().stream().noneMatch(g -> g.name().equals("shop")), "the team's group is not saved as your own");
        assertEquals(java.util.List.of("/home/dev/work/old"), saved.hiddenProjects());
    }

    @Test
    void readsWhereTheTeamFileIs() {
        TeamSettings p = TeamSettings.parse("https://gitlab.example.com/acme/platform/kitout-team");
        assertEquals("gitlab.example.com", p.host());
        assertEquals("acme/platform/kitout-team", p.project());
        assertEquals("kitout-team.json", p.path());
        assertEquals("main", p.ref());

        TeamSettings blob = TeamSettings.parse("https://gitlab.example.com/acme/platform/kitout-team/-/blob/release/setup/team.json");
        assertEquals("acme/platform/kitout-team", blob.project());
        assertEquals("release", blob.ref());
        assertEquals("setup/team.json", blob.path());

        assertEquals("~/team/kitout-team.json", TeamSettings.parse("~/team/kitout-team.json").file());
        assertThrows(IllegalArgumentException.class, () -> TeamSettings.parse("gitlab.example.com"));
    }

    @Test
    void theTeamFileIsNamedByRepositoryAndPathLikeAComposeFile() {
        TeamSettings t = TeamSettings.of("gitlab.example.com", "acme/infrastructure", "/kitout/kitout-team.json", null);
        assertEquals("gitlab.example.com", t.host());
        assertEquals("acme/infrastructure", t.project());
        assertEquals("kitout/kitout-team.json", t.path(), "a leading / is read from the repository root");
        assertEquals("main", t.ref());
        assertEquals(t, TeamSettings.parse("https://gitlab.example.com/acme/infrastructure/-/blob/main/kitout/kitout-team.json"),
                "the same place as the link GitLab shows");

        assertEquals("kitout-team.json", TeamSettings.of("https://gitlab.example.com/", "/acme/team/", "", "release").path());
        assertEquals("release", TeamSettings.of("gitlab.example.com", "acme/team", null, "release").ref());

        assertThrows(IllegalArgumentException.class, () -> TeamSettings.of("", "acme/team", null, null), "no host");
        assertThrows(IllegalArgumentException.class, () -> TeamSettings.of("gitlab.example.com/acme", "acme/team", null, null));
        assertThrows(IllegalArgumentException.class, () -> TeamSettings.of("gitlab.example.com", "team", null, null), "no group");
        IllegalArgumentException link = assertThrows(IllegalArgumentException.class,
                () -> TeamSettings.of("gitlab.example.com", "acme/infrastructure/-/blob/main/kitout/kitout-team.json", null, null));
        assertTrue(link.getMessage().contains("group/project only"), link.getMessage());
        assertThrows(IllegalArgumentException.class, () -> TeamSettings.of("gitlab.example.com", "acme/team", "-/blob/main/x.json", null));
        assertThrows(IllegalArgumentException.class, () -> TeamSettings.of("gitlab.example.com", "acme/team", "../x.json", null));
        assertThrows(IllegalArgumentException.class, () -> TeamSettings.of("gitlab.example.com", "acme/team", "kitout/", null));
        assertEquals("~/team/kitout-team.json", TeamSettings.ofFile(" ~/team/kitout-team.json ").file());
        assertThrows(IllegalArgumentException.class, () -> TeamSettings.of("github.com", "acme/infra/blob/main/kitout-team.json", null, null),
                "a GitHub link in the repository field");
    }

    @Test
    void gitHubLinksNameTheTeamFileToo() {
        TeamSettings expected = TeamSettings.of("github.com", "acme/infrastructure", "kitout/kitout-team.json", "main");
        assertEquals(expected, TeamSettings.parse("https://github.com/acme/infrastructure/blob/main/kitout/kitout-team.json"));
        assertEquals(expected, TeamSettings.parse("https://raw.githubusercontent.com/acme/infrastructure/main/kitout/kitout-team.json"));
        assertEquals(expected, TeamSettings.parse("github.com/acme/infrastructure/blob/main/kitout/kitout-team.json?plain=1"));
        TeamSettings project = TeamSettings.parse("https://github.com/acme/infrastructure");
        assertEquals("acme/infrastructure", project.project());
        assertEquals("kitout-team.json", project.path());
    }

    @Test
    void kitoutsBuildPresetsTheTeamFileByRepositoryAndPath() throws Exception {
        java.nio.file.Path home = java.nio.file.Files.createTempDirectory("kitout-preset");
        org.kitout.application.KitoutPaths paths = new org.kitout.application.KitoutPaths(java.util.Map.of("KITOUT_HOME", home.toString()));
        org.kitout.testsupport.Fakes.InMemoryConfig config = new org.kitout.testsupport.Fakes.InMemoryConfig(org.kitout.application.config.KitoutConfig.defaults());
        java.util.Optional<String> none = java.util.Optional.empty();

        TeamService fromRepo = new TeamService(config, null, null, org.kitout.testsupport.MiniJson.reader(), paths, "1.0.0",
                java.util.Optional.of("gitlab.example.com"), java.util.Optional.of("acme/infrastructure"),
                java.util.Optional.of("kitout/kitout-team.json"), none, java.util.Optional.of("https://old.example.com/a/b"));
        assertEquals(java.util.Optional.of(TeamSettings.of("gitlab.example.com", "acme/infrastructure", "kitout/kitout-team.json", "main")),
                fromRepo.preset(), "repo and path win over the older single location");

        TeamService old = new TeamService(config, null, null, org.kitout.testsupport.MiniJson.reader(), paths, "1.0.0",
                java.util.Optional.of("https://gitlab.example.com/acme/platform/kitout-team"));
        assertEquals("acme/platform/kitout-team", old.preset().orElseThrow().project(), "kitout.team.location still works");

        TeamService typo = new TeamService(config, null, null, org.kitout.testsupport.MiniJson.reader(), paths, "1.0.0",
                java.util.Optional.of("gitlab.example.com"), java.util.Optional.of("acme/infrastructure/-/blob/main/kitout-team.json"), none, none, none);
        assertTrue(typo.preset().isEmpty());
        assertTrue(typo.presetProblem().orElseThrow().contains("kitout.team"), "the Setup page says what is wrong in the build");
    }

    @Test
    void theTeamCanSwitchTheHealthCheckOffAndTheDeveloperBackOn() {
        org.kitout.domain.team.TeamSetup team = org.kitout.testsupport.TeamFixtures.filled();
        String bff = TeamOverlay.serviceId("acme/shop/mobile-bff");
        KitoutConfig personal = KitoutConfig.defaults().withService(bff, KitoutConfig.ServiceSettings.defaults("/w/mobile-bff"));
        KitoutConfig shown = TeamOverlay.apply(personal, team, java.time.LocalDate.of(2026, 11, 1));
        org.junit.jupiter.api.Assertions.assertFalse(shown.serviceSettings(bff).checksHealth(), "off by the team");

        KitoutConfig mine = personal.withService(bff, personal.serviceSettings(bff).withHealthCheck(true));
        org.junit.jupiter.api.Assertions.assertTrue(TeamOverlay.apply(mine, team, java.time.LocalDate.of(2026, 11, 1))
                .serviceSettings(bff).checksHealth(), "the developer's choice wins");
    }
}
