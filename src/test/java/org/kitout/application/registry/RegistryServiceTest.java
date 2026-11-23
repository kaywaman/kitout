package org.kitout.application.registry;

import org.kitout.application.port.CommandRunner.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryServiceTest {

    @Test
    void explainsLoginFailuresWithoutEchoingSecrets() {
        assertTrue(RegistryService.explainLogin("registry.example.com",
                new Result(1, "Error response from daemon: Get \"https://registry.example.com/v2/\": unauthorized: HTTP Basic: Access denied"))
                .contains("may differ from your git token"));
        assertTrue(RegistryService.explainLogin("registry.example.com", new Result(1, "dial tcp: lookup registry.example.com: no such host"))
                .contains("not reachable"));
        assertTrue(RegistryService.explainLogin("registry.example.com", new Result(-1, "")).contains("install Docker"));
    }
}
