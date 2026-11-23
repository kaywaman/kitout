package org.kitout.application.doctor.checks;

import org.kitout.application.docker.ComposeService.Environment;
import org.kitout.domain.config.HealthEndpoint;
import org.kitout.domain.doctor.CheckResult;
import org.kitout.domain.doctor.Severity;
import org.kitout.domain.identity.RemoteUrlParser;
import org.kitout.domain.project.BuildTool;
import org.kitout.domain.project.Project;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposePortCheckTest {

    private static Project service(String name, int port) {
        return new Project("acme.shop." + name, name, Path.of("/w/" + name), RemoteUrlParser.parse("https://g.example.com/acme/shop/" + name + ".git"),
                BuildTool.MAVEN, port, false, Optional.empty(), List.of(), Map.of(), new HealthEndpoint(port, "/q/health/ready", false));
    }

    @Test
    void warnsWhenAComposeFileRunsACopyOfAService() {
        Environment env = new Environment("dev", "dev", Path.of("/w/infra/docker-compose-dev.yml"), false);
        List<CheckResult> results = new ComposePortCheck(null, null).conflicts(env,
                Map.of("postgres", List.of(5432), "orders-service", List.of(8081)),
                List.of(service("orders", 8081), service("billing", 8082)));

        assertEquals(1, results.size());
        CheckResult r = results.get(0);
        assertEquals(Severity.WARNING, r.severity());
        assertTrue(r.detail().contains("port 8081"), r.detail());
        assertTrue(r.fix().orElseThrow().contains("Registry image"), "a copied service: let kitout run it");
    }

    @Test
    void recognisesTheSameServiceUnderUsualNames() {
        assertTrue(ComposePortCheck.sameName("orders", "orders"));
        assertTrue(ComposePortCheck.sameName("orders-service", "orders"));
        assertTrue(ComposePortCheck.sameName("acme_orders", "orders"));
        assertFalse(ComposePortCheck.sameName("postgres", "orders"));
        assertFalse(ComposePortCheck.sameName("preorders", "orders"));
    }
}
