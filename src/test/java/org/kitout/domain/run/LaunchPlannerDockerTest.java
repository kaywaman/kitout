package org.kitout.domain.run;

import org.kitout.domain.project.Project;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchPlannerDockerTest {

    @Test
    void mountsAndOptionsGoBetweenKitoutsOptionsAndTheImage() throws Exception {
        Project project = sampleProject();
        LaunchSpec spec = LaunchPlanner.image(project, "registry.example.com/acme/orders:mr-1", "dev", Map.of("A", "1"),
                List.of("type=bind,source=/h,target=/c"), List.of("--memory", "1g"));
        List<String> cmd = spec.command();
        assertEquals("registry.example.com/acme/orders:mr-1", cmd.get(cmd.size() - 1), "the image stays last");
        int label = cmd.indexOf("--label");
        assertEquals(LaunchPlanner.LABEL + "=" + project.id(), cmd.get(label + 1));
        int mount = cmd.indexOf("--mount");
        int memory = cmd.indexOf("--memory");
        assertTrue(cmd.indexOf("-e") < mount && mount < memory, String.join(" ", cmd));
        assertEquals("type=bind,source=/h,target=/c", cmd.get(mount + 1));
    }

    /** A project read from a minimal folder, as the other run tests do. */
    static Project sampleProject() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("kitout-plan").resolve("orders");
        java.nio.file.Files.createDirectories(dir.resolve("src/main/resources"));
        java.nio.file.Files.writeString(dir.resolve("pom.xml"), "<project><groupId>io.quarkus</groupId></project>");
        java.nio.file.Files.writeString(dir.resolve("src/main/resources/application.properties"), "quarkus.http.port=8081\n");
        org.kitout.application.workspace.ProjectReader reader = new org.kitout.application.workspace.ProjectReader(
                new org.kitout.application.git.GitCli(new org.kitout.infrastructure.process.ProcessCommandRunner()));
        return reader.read(dir).orElseThrow();
    }
}
