package org.kitout.domain.team;

import org.kitout.testsupport.TeamFixtures;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamLibrariesAndBffsTest {

    @Test
    void theExampleHasTwoBffsTwoLibrariesAndOneHealthCheckSwitchedOff() {
        TeamSetup t = TeamFixtures.filled();
        assertEquals(List.of("acme/shop/bff", "acme/shop/mobile-bff"), t.groups().get(0).bffs());
        assertEquals(false, t.service("acme/shop/mobile-bff").orElseThrow().healthCheck());
        assertNull(t.service("acme/shop/orders").orElseThrow().healthCheck(), "not set: kitout's default");

        List<TeamSetup.Repository> others = t.nonServiceRepositories();
        assertEquals(List.of("repository", "java-library", "ui-library"), others.stream().map(TeamSetup.Repository::kind).toList());
        assertTrue(t.repositories().containsAll(List.of("acme/common/java-commons", "acme/common/ui-components")), "libraries are cloned");
    }

    @Test
    void aSingleBffIsStillRead() {
        Map<String, Object> json = TeamFixtures.filledJson();
        @SuppressWarnings("unchecked")
        Map<String, Object> group = (Map<String, Object>) ((List<Object>) json.get("groups")).get(0);
        group.remove("bffs");
        group.put("bff", "acme/shop/bff");
        TeamSetup t = TeamSetupParser.parse(json, "1.0.0").setup().orElseThrow();
        assertEquals(List.of("acme/shop/bff"), t.groups().get(0).bffs());
    }

    @Test
    void aBffMustBeAMemberAndALibraryCannotAlsoBeAService() {
        Map<String, Object> json = TeamFixtures.filledJson();
        @SuppressWarnings("unchecked")
        Map<String, Object> group = (Map<String, Object>) ((List<Object>) json.get("groups")).get(0);
        group.put("bffs", List.of("acme/shop/other"));
        @SuppressWarnings("unchecked")
        List<Object> libs = (List<Object>) json.get("uiLibraries");
        libs.add(new LinkedHashMap<>(Map.of("repo", "acme/shop/orders")));
        TeamSetupParser.Result r = TeamSetupParser.parse(json, "1.0.0");
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("acme/shop/other is not one of the group's members")), r.problems().toString());
        assertTrue(r.problems().stream().anyMatch(p -> p.startsWith("uiLibraries[1].repo acme/shop/orders is listed twice")), r.problems().toString());
    }

    @Test
    void healthCheckMustBeTrueOrFalse() {
        Map<String, Object> json = TeamFixtures.filledJson();
        @SuppressWarnings("unchecked")
        Map<String, Object> svc = (Map<String, Object>) ((List<Object>) json.get("services")).get(0);
        svc.put("healthCheck", "no");
        assertTrue(TeamSetupParser.parse(json, "1.0.0").problems().contains("services[0].healthCheck must be true or false"));
    }

    @Test
    void aComposePathWithALeadingSlashIsInsideTheRepository() {
        Map<String, Object> json = TeamFixtures.filledJson();
        @SuppressWarnings("unchecked")
        Map<String, Object> env = (Map<String, Object>) ((List<Object>) ((Map<String, Object>) json.get("compose")).get("environments")).get(0);
        env.put("files", List.of(new LinkedHashMap<>(Map.of("repo", "acme/platform/infrastructure", "path", "/compose/docker-compose-dev.yml"))));
        TeamSetupParser.Result r = TeamSetupParser.parse(json, "1.0.0");
        assertTrue(r.problems().isEmpty(), r.problems().toString());
        assertEquals("compose/docker-compose-dev.yml", r.setup().orElseThrow().compose().get(0).files().get(0).path());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("read as relative to the repository root")), r.warnings().toString());

        env.put("files", List.of(new LinkedHashMap<>(Map.of("repo", "acme/platform/infrastructure", "path", "compose/../../etc/x.yml"))));
        assertTrue(TeamSetupParser.parse(json, "1.0.0").problems().stream().anyMatch(p -> p.contains("no ..")));
    }
}
