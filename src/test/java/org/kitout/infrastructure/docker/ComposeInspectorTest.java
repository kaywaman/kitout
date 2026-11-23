package org.kitout.infrastructure.docker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComposeInspectorTest {

    @Test
    void readsPublishedPortsAndRanges() {
        assertEquals(List.of(5432), DockerComposeInspector.range("5432"));
        assertEquals(List.of(8080, 8081, 8082), DockerComposeInspector.range("8080-8082"));
        assertEquals(List.of(), DockerComposeInspector.range(""));
        assertEquals(List.of(), DockerComposeInspector.range("abc"));
    }
}
