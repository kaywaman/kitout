package org.kitout.application.env;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.env.EnvironmentService.Change;
import org.kitout.application.env.EnvironmentService.Entry;
import org.kitout.testsupport.Fakes;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentServiceTest {

    private final Fakes.InMemoryConfig config = new Fakes.InMemoryConfig(KitoutConfig.defaults()
            .withService("acme.orders", KitoutConfig.ServiceSettings.defaults("/w/orders")));
    private final Fakes.InMemorySecrets secrets = new Fakes.InMemorySecrets();

    private EnvironmentService service() throws Exception {
        // only the shared scope and services already in the config are used: no workspace scan needed
        return new EnvironmentService(config, secrets, new org.kitout.application.workspace.WorkspaceService(null, config,
                new KitoutPaths(Map.of("KITOUT_HOME", Files.createTempDirectory("kitout-env").toString()))) {
            @Override
            public org.kitout.domain.project.Project require(String id) {
                return null;
            }
        }, org.kitout.application.team.TeamDefaults.NONE);
    }

    @Test
    void serviceValuesWinOverSharedOnesAndSecretsStayInTheKeyring() throws Exception {
        EnvironmentService env = service();
        env.save(EnvironmentService.SHARED, List.of(
                new Change("quarkus.datasource.username", "dev", false),
                new Change("QUARKUS_DATASOURCE_PASSWORD", "shared-pw", true)));
        env.save("acme.orders", List.of(new Change("QUARKUS_DATASOURCE_USERNAME", "orders", false)));

        Map<String, String> resolved = env.resolve("acme.orders");
        assertEquals("orders", resolved.get("QUARKUS_DATASOURCE_USERNAME"), "the service's own value wins");
        assertEquals("shared-pw", resolved.get("QUARKUS_DATASOURCE_PASSWORD"));

        Entry secret = env.list(EnvironmentService.SHARED).get(1);
        assertTrue(secret.secret());
        assertNull(secret.value(), "secret values are never sent back");
        assertTrue(secret.set());
        assertNull(config.load().runtime().environment().get(1).value(), "and never written to config.json");
    }

    @Test
    void aSecretKeepsItsValueUntilChangedAndIsDeletedWhenRemoved() throws Exception {
        EnvironmentService env = service();
        env.save(EnvironmentService.SHARED, List.of(new Change("DB_PASSWORD", "one", true)));
        env.save(EnvironmentService.SHARED, List.of(new Change("DB_PASSWORD", null, true)));
        assertEquals("one", env.resolve("acme.orders").get("DB_PASSWORD"), "null keeps the stored value");

        env.save(EnvironmentService.SHARED, List.of());
        assertFalse(secrets.lookup(EnvironmentService.key(EnvironmentService.SHARED, "DB_PASSWORD")).isPresent());
        assertThrows(IllegalArgumentException.class, () -> env.save(EnvironmentService.SHARED, List.of(new Change("NEW_SECRET", null, true))),
                "a new secret needs a value");
        assertThrows(IllegalArgumentException.class, () -> env.save(EnvironmentService.SHARED,
                List.of(new Change("quarkus.http.port", "1", false), new Change("QUARKUS_HTTP_PORT", "2", false))),
                "a property and its variable are the same name");
    }

    private EnvironmentService withTeam() throws Exception {
        org.kitout.domain.team.TeamSetup team = org.kitout.testsupport.TeamFixtures.filled();
        return new EnvironmentService(config, secrets, new org.kitout.application.workspace.WorkspaceService(null, config,
                new KitoutPaths(Map.of("KITOUT_HOME", Files.createTempDirectory("kitout-env").toString()))) {
            @Override
            public org.kitout.domain.project.Project require(String id) {
                return null;
            }
        }, () -> java.util.Optional.of(team));
    }

    @Test
    void theTeamsValuesComeFirstAndItsSecretsMustBeEntered() throws Exception {
        EnvironmentService env = withTeam();
        assertEquals(List.of("MONGO_PASSWORD"), env.missingTeamSecrets());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> env.resolve("acme.orders"));
        assertTrue(e.getMessage().contains("MONGO_PASSWORD"));

        env.setTeamSecret(EnvironmentService.SHARED, "MONGO_PASSWORD", "s3cret");
        env.save(EnvironmentService.SHARED, List.of(new Change("MONGO_HOST", "mongo.local", false)));
        Map<String, String> resolved = env.resolve("acme.orders");
        assertEquals("mongo.local", resolved.get("MONGO_HOST"), "your own value wins over the team's");
        assertEquals("27017", resolved.get("MONGO_PORT"));
        assertEquals("s3cret", resolved.get("MONGO_PASSWORD"));
        assertTrue(env.missingTeamSecrets().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> env.setTeamSecret(EnvironmentService.SHARED, "OTHER", "x"));
    }

    @Test
    void importsADotEnvFile() throws Exception {
        EnvironmentService env = withTeam();
        EnvironmentService.ImportResult r = env.importDotEnv(EnvironmentService.SHARED, """
                # local Mongo
                MONGO_HOST=localhost
                MONGO_PORT=27018
                export MONGO_USERNAME="admin"
                MONGO_PASSWORD='pa ss'
                API_KEY=abc123 # from the wiki
                1BAD=x
                """);
        assertEquals(List.of("MONGO_PORT", "MONGO_USERNAME"), r.added());
        assertEquals(List.of("MONGO_PASSWORD", "API_KEY"), r.secrets());
        assertEquals(2, r.skipped().size(), r.skipped().toString());   // MONGO_HOST same as the team's, 1BAD invalid
        Map<String, String> resolved = env.resolve("acme.orders");
        assertEquals("pa ss", resolved.get("MONGO_PASSWORD"));
        assertEquals("abc123", resolved.get("API_KEY"));
        assertEquals("admin", resolved.get("MONGO_USERNAME"));
        assertNull(config.load().runtime().environment().stream().filter(v -> v.name().equals("API_KEY")).findFirst().orElseThrow().value(),
                "secrets never reach config.json");
    }

    @Test
    void removingYourOwnLineKeepsTheTeamSecret() throws Exception {
        EnvironmentService env = withTeam();
        env.save(EnvironmentService.SHARED, List.of(new Change("MONGO_PASSWORD", "mine", true)));
        env.save(EnvironmentService.SHARED, List.of());
        assertTrue(env.missingTeamSecrets().isEmpty(), "the keyring entry the team secret uses stays");
    }

    @Test
    void buildVariablesGoToBuildsOnlyRunVariablesToStartsOnly() throws Exception {
        EnvironmentService env = service();
        env.save(EnvironmentService.SHARED, List.of(
                new Change("MONGO_HOST", "localhost", false, KitoutConfig.EnvUse.RUN),
                new Change("MAVEN_OPTS", "-Xmx2g", false, KitoutConfig.EnvUse.BUILD),
                new Change("NEXUS_TOKEN", "t0k3n", true, KitoutConfig.EnvUse.BUILD),
                new Change("TZ", "Europe/Berlin", false, KitoutConfig.EnvUse.BOTH)));
        env.save("acme.orders", List.of(new Change("MAVEN_OPTS", "-Xmx4g", false, KitoutConfig.EnvUse.BUILD)));

        assertEquals(Map.of("MONGO_HOST", "localhost", "TZ", "Europe/Berlin"), env.resolve("acme.orders"));
        assertEquals(Map.of("MAVEN_OPTS", "-Xmx4g", "NEXUS_TOKEN", "t0k3n", "TZ", "Europe/Berlin"), env.resolveBuild("acme.orders"),
                "the service's own build value wins; secrets come from the keyring");
        assertEquals(KitoutConfig.EnvUse.BUILD, env.list(EnvironmentService.SHARED).get(2).use());
    }

    @Test
    void variablesFromBeforeBuildsHadAnyAreForRunning() {
        KitoutConfig.EnvVar old = new KitoutConfig.EnvVar("A", "1", false, null);
        assertEquals(KitoutConfig.EnvUse.RUN, old.use());
        assertEquals(KitoutConfig.EnvUse.BOTH, KitoutConfig.EnvUse.parse("both"));
        assertEquals(KitoutConfig.EnvUse.RUN, KitoutConfig.EnvUse.parse(null));
        assertThrows(IllegalArgumentException.class, () -> KitoutConfig.EnvUse.parse("deploy"));
    }

    @Test
    void aMissingBuildSecretSaysWhereToEnterIt() throws Exception {
        EnvironmentService env = service();
        env.save(EnvironmentService.SHARED, List.of(new Change("NEXUS_TOKEN", "t", true, KitoutConfig.EnvUse.BUILD)));
        secrets.delete(EnvironmentService.key(EnvironmentService.SHARED, "NEXUS_TOKEN"));
        assertTrue(assertThrows(IllegalStateException.class, () -> env.resolveBuild("acme.orders")).getMessage().contains("NEXUS_TOKEN"));
        env.resolve("acme.orders");   // starting is not affected
    }
}
