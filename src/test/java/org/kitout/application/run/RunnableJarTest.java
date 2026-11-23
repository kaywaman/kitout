package org.kitout.application.run;

import org.kitout.domain.project.BuildTool;
import org.kitout.domain.project.ProjectType;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnableJarTest {

    private static void jar(Path file, String mainClass) throws Exception {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) {
            m.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        try (OutputStream out = Files.newOutputStream(file); JarOutputStream j = new JarOutputStream(out, m)) {
            j.flush();
        }
    }

    @Test
    void picksTheJarWithAMainClassAndSkipsThePlainAndSourceJars() throws Exception {
        Path dir = Files.createTempDirectory("kitout-jar");
        Path target = Files.createDirectories(dir.resolve("target"));
        jar(target.resolve("payments-1.0.jar"), "org.springframework.boot.loader.launch.JarLauncher");
        jar(target.resolve("payments-1.0-plain.jar"), "x.Main");
        jar(target.resolve("payments-1.0-sources.jar"), null);
        jar(target.resolve("original-payments-1.0.jar"), null);
        org.kitout.domain.project.Project here = new org.kitout.domain.project.Project("acme.payments", "payments", dir, java.util.Optional.empty(),
                BuildTool.MAVEN, 8080, false, java.util.Optional.empty(), java.util.List.of(), java.util.Map.of(),
                new org.kitout.domain.config.HealthEndpoint(8080, "/actuator/health", false), java.util.Optional.empty(),
                ProjectType.SPRING_BOOT, java.util.List.of(), java.util.Optional.empty());

        assertEquals("payments-1.0.jar", new ArtifactLocator().jvmArtifact(here).orElseThrow().getFileName().toString());
        Files.delete(target.resolve("payments-1.0.jar"));
        assertTrue(new ArtifactLocator().jvmArtifact(here).isEmpty(), "no runnable jar left: the start says to build it");
    }
}
