package org.kitout.domain.docker;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposeModelTest {

    @Test
    void environmentComesFromTheFileName() {
        assertEquals(Optional.of("dev"), ComposeFile.environmentOf("docker-compose-dev.yml"));
        assertEquals(Optional.of("qa"), ComposeFile.environmentOf("docker-compose-qa.yaml"));
        assertEquals(Optional.of("test"), ComposeFile.environmentOf("docker-compose.test.yml"));
        assertEquals(Optional.of("local"), ComposeFile.environmentOf("compose-local.yml"));
        assertEquals(Optional.of("default"), ComposeFile.environmentOf("docker-compose.yml"));
        assertEquals(Optional.empty(), ComposeFile.environmentOf("docker-compose.override.yml"), "an overlay, not an environment");
        assertEquals(Optional.empty(), ComposeFile.environmentOf("docker-compose-dev.yml.bak"));
        assertEquals(Optional.empty(), ComposeFile.environmentOf("README.md"));
    }

    @Test
    void pickedFilesKeepTheirName() {
        assertEquals("dev", ComposeFile.nameForPicked("docker-compose-dev.yml"));
        assertEquals("kafka-debug", ComposeFile.nameForPicked("kafka-debug.yml"));
        assertEquals("Local.Stack", ComposeFile.nameForPicked("Local.Stack.YAML"));
        assertTrue(ComposeFile.looksLikeCompose("version: '3'\nservices:\n  db: {}\n"));
        assertFalse(ComposeFile.looksLikeCompose("quarkus:\n  services: x\n"), "nested keys do not count");
    }

    @Test
    void readsDockerPsLines() {
        String line = String.join("\t", "abc123", "infra-postgres-1", "postgres:17", "running", "Up 3 minutes (healthy)",
                "0.0.0.0:5432->5432/tcp", "infra", "/home/dev/infra/docker-compose-dev.yml", "");
        Container c = Container.parse(line).orElseThrow();
        assertEquals("infra-postgres-1", c.name());
        assertTrue(c.running());
        assertEquals(List.of("/home/dev/infra/docker-compose-dev.yml"), c.composeFiles());
        assertTrue(c.createdFrom("/home/dev/infra/docker-compose-dev.yml"));
        assertFalse(c.createdFrom("/home/dev/infra/docker-compose-test.yml"));
        assertEquals("", c.kitoutService());

        Container kitout = Container.parse(String.join("\t", "def", "kitout-orders", "registry/x/orders:latest", "exited",
                "Exited (1) 2 hours ago", "", "", "", "acme.shop.orders")).orElseThrow();
        assertFalse(kitout.running());
        assertEquals(List.of(), kitout.composeFiles());
        assertEquals("acme.shop.orders", kitout.kitoutService());

        assertTrue(Container.parse("").isEmpty());
        assertTrue(Container.parse("only\tthree\tfields").isEmpty());
    }
}
