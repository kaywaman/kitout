package org.kitout.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kitout.application.KitoutPaths;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.config.KitoutConfig.ServiceSettings;
import org.kitout.application.config.KitoutConfig.VolumeMount;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigSchemaTwoTest {

    private static JsonConfigRepository repository(Path home) {
        return new JsonConfigRepository(new KitoutPaths(Map.of("KITOUT_HOME", home.toString())), new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void aGroupsOneBffBecomesAListAndOldServicesKeepTheirDefaults() throws Exception {
        Path home = Files.createTempDirectory("kitout-schema");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/config.json"), """
                {"schemaVersion":1,
                 "services":{"acme.orders":{"path":"/w/orders","runMode":"IMAGE","imageTag":"mr-1"}},
                 "groups":[{"name":"shop","members":["acme.orders","acme.bff"],"bff":"acme.bff","sequentialStart":false,"suggested":false},
                           {"name":"tools","members":["acme.orders"],"bff":null}]}
                """);

        KitoutConfig cfg = repository(home).load();

        assertEquals(List.of("acme.bff"), cfg.group("shop").orElseThrow().bffs());
        assertEquals(List.of(), cfg.group("tools").orElseThrow().bffs());
        ServiceSettings orders = cfg.serviceSettings("acme.orders");
        assertEquals("mr-1", orders.imageTag());
        assertEquals(List.of(), orders.volumes());
        assertNull(orders.dockerArgs());
        assertTrue(orders.checksHealth(), "an old file has the health check on");
        assertTrue(Files.exists(home.resolve("config/config.json.bak-v1")), "the old file is kept");
    }

    @Test
    void newSettingsSurviveASaveAndLoad() throws Exception {
        Path home = Files.createTempDirectory("kitout-schema");
        JsonConfigRepository repo = repository(home);
        repo.update(c -> c.withService("acme.bff", ServiceSettings.defaults("/w/bff")
                .withDocker(List.of(new VolumeMount("~/certs", "/deployments/certs", true)), "--memory 1g")
                .withHealthCheck(false)
                .withEnvironment(List.of(new KitoutConfig.EnvVar("MAVEN_OPTS", "-Xmx2g", false, KitoutConfig.EnvUse.BUILD)))));

        ServiceSettings bff = repository(home).load().serviceSettings("acme.bff");

        assertEquals(List.of(new VolumeMount("~/certs", "/deployments/certs", true)), bff.volumes());
        assertEquals("--memory 1g", bff.dockerArgs());
        assertEquals(false, bff.checksHealth());
        assertEquals(KitoutConfig.EnvUse.BUILD, bff.environment().get(0).use());
        String saved = Files.readString(home.resolve("config/config.json")).replace(" ", "");
        assertTrue(saved.contains("\"schemaVersion\":" + KitoutConfig.CURRENT_SCHEMA), saved);
    }

    @Test
    void aSchemaTwoFileKeepsItsProfiles() throws Exception {
        Path home = Files.createTempDirectory("kitout-schema");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/config.json"),
                "{\"schemaVersion\":2,\"build\":{\"nativeContainerBuild\":true,\"nativeMinFreeMemoryMb\":6144,\"profiles\":[\"dev\",\"test\",\"staging\"]}}");
        assertEquals(List.of("dev", "test", "staging"), repository(home).load().build().profiles());
        assertEquals(List.of("dev", "qa", "prod"), KitoutConfig.defaults().build().profiles());
    }
}
