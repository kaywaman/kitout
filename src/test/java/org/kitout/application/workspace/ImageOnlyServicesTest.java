package org.kitout.application.workspace;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.run.ServiceSupervisor;
import org.kitout.domain.project.Project;
import org.kitout.domain.project.ProjectType;
import org.kitout.domain.run.RunMode;
import org.kitout.domain.team.TeamSetup;
import org.kitout.domain.team.TeamSetupParser;
import org.kitout.testsupport.Fakes;
import org.kitout.testsupport.TeamFixtures;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageOnlyServicesTest {

    private static Map<String, Object> withImages(List<Map<String, Object>> images) {
        Map<String, Object> json = TeamFixtures.filledJson();
        json.put("images", new java.util.ArrayList<>(images));
        return json;
    }

    private static Map<String, Object> image(String name, String image, Object port, String health) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("image", image);
        if (port != null) {
            m.put("port", port);
        }
        if (health != null) {
            m.put("healthPath", health);
        }
        return m;
    }

    @Test
    void theTeamFileNamesThemAndGroupsCanUseThem() {
        Map<String, Object> json = withImages(List.of(image("payments", "other-team/payments", 8090, "/actuator/health")));
        @SuppressWarnings("unchecked")
        Map<String, Object> group = (Map<String, Object>) ((List<Object>) json.get("groups")).get(0);
        @SuppressWarnings("unchecked")
        List<Object> members = new java.util.ArrayList<>((List<Object>) group.get("members"));
        members.add("payments");
        group.put("members", members);

        TeamSetupParser.Result r = TeamSetupParser.parse(json, "1.0.0");

        assertTrue(r.problems().isEmpty(), r.problems().toString());
        TeamSetup.ImageService p = r.setup().orElseThrow().images().get(0);
        assertEquals("payments", p.id());
        assertEquals(8090, p.port());
    }

    @Test
    void aTagAMissingPortOrARelativeHealthPathAreRefused() {
        TeamSetupParser.Result r = TeamSetupParser.parse(withImages(List.of(
                image("payments", "other-team/payments:1.2", null, "health"))), "1.0.0");
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("names a tag")), r.problems().toString());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("port is required")), r.problems().toString());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("must start with /")), r.problems().toString());
    }

    @Test
    void theyAreListedWithoutACheckoutAndRunFromTheirImageOnly() throws Exception {
        TeamSetup team = TeamSetupParser.parse(withImages(List.of(image("payments", "other-team/payments", 8090, null))), "1.0.0")
                .setup().orElseThrow();
        Fakes.InMemoryConfig config = new Fakes.InMemoryConfig(KitoutConfig.defaults());
        KitoutPaths paths = new KitoutPaths(Map.of("KITOUT_HOME", Files.createTempDirectory("kitout-images").toString()));
        WorkspaceService workspace = new WorkspaceService(null, config, paths, () -> Optional.of(team));

        Project p = workspace.require("payments");

        assertEquals(ProjectType.IMAGE, p.type());
        assertEquals(Optional.of("other-team/payments"), p.image());
        assertTrue(!p.hasCheckout() && !p.hasHealthEndpoint());
        assertEquals(List.of(p), workspace.projects());
        KitoutConfig.ServiceSettings jvm = KitoutConfig.ServiceSettings.defaults(null).withRunMode(RunMode.JVM);
        assertEquals(RunMode.IMAGE, ServiceSupervisor.fit(p, jvm).runMode(), "the team default 'jvm' does not apply to it");

        config.update(c -> c.withService("payments", c.serviceSettings("payments").withPortAndScript(9095, null)));
        assertEquals(9095, workspace.require("payments").devPort(), "the developer's port wins everywhere");
    }
}
