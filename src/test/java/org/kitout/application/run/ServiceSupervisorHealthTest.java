package org.kitout.application.run;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.config.KitoutConfig.ServiceSettings;
import org.kitout.application.git.GitCli;
import org.kitout.application.port.NetworkProbe;
import org.kitout.application.port.ProcessControl;
import org.kitout.application.workspace.ProjectReader;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.domain.run.RunMode;
import org.kitout.domain.run.ServiceHealth;
import org.kitout.domain.run.ServiceState;
import org.kitout.infrastructure.process.ProcessCommandRunner;
import org.kitout.testsupport.Fakes;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lifecycle rules around readiness, with a fake process and a scripted readiness check:
 * STARTING until the first answer, then RUNNING for as long as the process lives, healthy or not.
 */
class ServiceSupervisorHealthTest {

    /** Readiness answers that the test switches between. */
    static final class ScriptedNetwork implements NetworkProbe {
        volatile HealthCheck answer = HealthCheck.of(Health.UNREACHABLE);
        volatile String askedPath;
        volatile int askedPort;
        volatile boolean portOpen;
        volatile int healthAsked;

        @Override
        public boolean canConnect(String host, int port, Duration timeout) {
            return portOpen;
        }

        @Override
        public HealthCheck health(int port, String path) {
            healthAsked++;
            askedPort = port;
            askedPath = path;
            return answer;
        }

        @Override
        public Optional<String> portHolder(int port) {
            return Optional.empty();
        }
    }

    static final class AliveProcesses implements ProcessControl {
        volatile boolean alive = true;

        @Override
        public ProcessRef spawn(List<String> command, Path dir, Map<String, String> env, Path log) {
            return new ProcessRef(4242, Instant.now());
        }

        @Override
        public boolean isAlive(ProcessRef ref) {
            return alive;
        }

        @Override
        public void terminate(ProcessRef ref, Duration grace) {
            alive = false;
        }

        @Override
        public Optional<Integer> exitCode(ProcessRef ref) {
            return Optional.empty();
        }
    }

    private final ScriptedNetwork network = new ScriptedNetwork();
    private final AliveProcesses processes = new AliveProcesses();
    private final Fakes.InMemoryState state = new Fakes.InMemoryState();

    private ServiceSupervisor supervisor(String properties) throws Exception {
        Path root = Files.createTempDirectory("kitout-health");
        Path project = root.resolve("orders");
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(project.resolve("pom.xml"), "<project><groupId>io.quarkus</groupId></project>");
        Files.writeString(project.resolve("src/main/resources/application.properties"), properties);
        Path jar = project.resolve("target/quarkus-app/quarkus-run.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "");

        ProcessCommandRunner runner = new ProcessCommandRunner();
        ProjectReader reader = new ProjectReader(new GitCli(runner));
        KitoutPaths paths = new KitoutPaths(Map.of("KITOUT_HOME", root.resolve("home").toString()));
        String id = reader.read(project).orElseThrow().id();
        Fakes.InMemoryConfig config = new Fakes.InMemoryConfig(KitoutConfig.defaults()
                .withService(id, ServiceSettings.defaults(project.toString()).withRunMode(RunMode.JVM)));
        lastConfig = config;
        WorkspaceService workspace = new WorkspaceService(reader, config, paths);
        return new ServiceSupervisor(workspace, config, state, processes, network, new DockerCli(runner),
                new ArtifactLocator(), new LogService(), paths,
                new org.kitout.application.registry.RegistryLogins(host -> java.util.Optional.empty(), new DockerCli(runner)),
                new org.kitout.application.env.EnvironmentService(config, new Fakes.InMemorySecrets(), workspace, org.kitout.application.team.TeamDefaults.NONE));
    }

    private Fakes.InMemoryConfig lastConfig;

    @Test
    void noKitoutBuildWhileTheServiceRunsFromItsOutputOrBelongsToTheIde() throws Exception {
        ServiceSupervisor s = supervisor("quarkus.http.port=8081\n");
        assertTrue(s.cleanBuildConflict("orders").isEmpty(), "stopped JVM service: build away");
        s.start("orders");
        assertTrue(s.cleanBuildConflict("orders").orElseThrow().startsWith("Stop orders first"));
        s.stop("orders");
        lastConfig.update(c -> c.withService("orders", c.serviceSettings("orders").withRunMode(RunMode.IDE)));
        assertTrue(s.cleanBuildConflict("orders").orElseThrow().contains("IDE mode"), "IDE mode: hands off, running or not");
    }

    @Test
    void startingUntilFirstAnswerThenRunningAndHealthy() throws Exception {
        ServiceSupervisor s = supervisor("quarkus.http.port=8081\n");
        assertEquals(ServiceState.STARTING, s.start("orders").state());

        network.answer = NetworkProbe.HealthCheck.of(NetworkProbe.Health.READY);
        ServiceStatus status = s.status("orders");

        assertEquals(ServiceState.RUNNING, status.state());
        assertEquals(ServiceHealth.HEALTHY, status.health());
        assertTrue(status.startupTime().isPresent(), "the start time is recorded");
        assertEquals("/q/health/ready", network.askedPath);
    }

    @Test
    void unhealthyAfterStartStaysRunningAndNamesTheDownCheck() throws Exception {
        ServiceSupervisor s = supervisor("quarkus.http.port=8081\n");
        s.start("orders");
        network.answer = NetworkProbe.HealthCheck.of(NetworkProbe.Health.READY);
        s.status("orders");

        network.answer = new NetworkProbe.HealthCheck(NetworkProbe.Health.NOT_READY, List.of("Database connections health check"));
        ServiceStatus status = s.status("orders");

        assertEquals(ServiceState.RUNNING, status.state(), "unhealthy is not failed");
        assertEquals(ServiceHealth.UNHEALTHY, status.health());
        assertTrue(status.detail().contains("Database connections health check"));
        assertTrue(processes.alive, "kitout does not stop an unhealthy service");
    }

    @Test
    void noAnswerAtAllAfterStartIsUnhealthyToo() throws Exception {
        ServiceSupervisor s = supervisor("");
        s.start("orders");
        network.answer = NetworkProbe.HealthCheck.of(NetworkProbe.Health.NO_HEALTH_ENDPOINT);
        assertEquals(ServiceHealth.NO_HEALTH_CHECK, s.status("orders").health());

        network.answer = NetworkProbe.HealthCheck.of(NetworkProbe.Health.UNREACHABLE);
        ServiceStatus status = s.status("orders");
        assertEquals(ServiceState.RUNNING, status.state());
        assertEquals(ServiceHealth.UNHEALTHY, status.health());
    }

    @Test
    void deadProcessIsFailed() throws Exception {
        ServiceSupervisor s = supervisor("");
        s.start("orders");
        network.answer = NetworkProbe.HealthCheck.of(NetworkProbe.Health.READY);
        s.status("orders");
        processes.alive = false;
        assertEquals(ServiceState.FAILED, s.status("orders").state());
    }

    @Test
    void asksTheHealthPathFromTheProjectConfig() throws Exception {
        ServiceSupervisor s = supervisor("quarkus.management.enabled=true\nquarkus.management.port=9101\n");
        s.start("orders");
        s.status("orders");
        assertEquals(9101, network.askedPort);
        assertEquals("/q/health/ready", network.askedPath);
    }

    @Test
    void withTheHealthCheckOffStartedMeansThePortAnswers() throws Exception {
        ServiceSupervisor s = supervisor("quarkus.http.port=8081\n");
        lastConfig.update(c -> c.withService("orders", c.serviceSettings("orders").withHealthCheck(false)));
        network.answer = NetworkProbe.HealthCheck.of(NetworkProbe.Health.NOT_READY);   // blocked endpoint: must not matter

        assertEquals(ServiceState.STARTING, s.start("orders").state());
        assertEquals("Waiting for port 8081", s.status("orders").detail());

        network.portOpen = true;
        int asked = network.healthAsked;
        ServiceStatus status = s.status("orders");

        assertEquals(ServiceState.RUNNING, status.state());
        assertEquals(ServiceHealth.NO_HEALTH_CHECK, status.health());
        assertTrue(status.detail().contains("Health check switched off"), status.detail());
        assertEquals(asked, network.healthAsked, "the readiness endpoint is never asked");

        network.portOpen = false;
        ServiceStatus gone = s.status("orders");
        assertEquals(ServiceState.RUNNING, gone.state(), "the process lives: still running");
        assertEquals(ServiceHealth.UNHEALTHY, gone.health());
        assertEquals("No answer on port 8081", gone.detail());
    }
}
