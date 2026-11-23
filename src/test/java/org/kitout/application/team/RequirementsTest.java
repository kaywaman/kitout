package org.kitout.application.team;

import org.kitout.application.port.CommandRunner;
import org.kitout.application.team.RequirementsService.Outcome;
import org.kitout.application.team.RequirementsService.Result;
import org.kitout.domain.team.TeamSetup.Check;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);

    private static Check check(String id, String require, Optional<LocalDate> from) {
        return new Check(id, null, require, null, List.of(), null, from);
    }

    @Test
    void aMetRequirementPasses() {
        Result r = RequirementsService.evaluate(check("docker", ">=28.5.2", Optional.empty()),
                new MachineProbe.Reading("28.5.2", "Docker Engine 28.5.2", null), TODAY, true);
        assertEquals(Outcome.PASS, r.outcome());
        assertNull(r.problem());
    }

    @Test
    void aFailedRequirementBlocksAndSaysHowToFixIt() {
        Result r = RequirementsService.evaluate(check("node", ">=22.23.4", Optional.empty()),
                new MachineProbe.Reading("v20.11.1", "Node.js v20.11.1", null), TODAY, true);
        assertEquals(Outcome.FAIL, r.outcome());
        assertEquals("Node.js v20.11.1 does not meet >=22.23.4", r.problem());
        assertTrue(r.fix().contains("nvm install"), "kitout's own advice when the team file gives none");
    }

    @Test
    void theTeamsFixTextWins() {
        Check c = new Check("git", "git", ">=2.43.0", null, List.of(), "Ask IT for the developer image", Optional.empty());
        Result r = RequirementsService.evaluate(c, new MachineProbe.Reading("2.34.1", "git 2.34.1", null), TODAY, true);
        assertEquals("Ask IT for the developer image", r.fix());
    }

    @Test
    void aRaisedRequirementOnlyWarnsUntilItsDay() {
        Check c = check("memory", ">=16384", Optional.of(LocalDate.of(2026, 12, 1)));
        MachineProbe.Reading eightGb = new MachineProbe.Reading("7821", "7821 MB", null);
        assertEquals(Outcome.WARN, RequirementsService.evaluate(c, eightGb, TODAY, true).outcome());
        assertEquals(Outcome.FAIL, RequirementsService.evaluate(c, eightGb, LocalDate.of(2026, 12, 1), true).outcome());
    }

    @Test
    void enforcementWarnNeverBlocks() {
        Result r = RequirementsService.evaluate(check("git", ">=2.43.0", Optional.empty()),
                new MachineProbe.Reading("2.34.1", "", null), TODAY, false);
        assertEquals(Outcome.WARN, r.outcome());
    }

    @Test
    void aProblemFailsEvenWhenTheVersionMatches() {
        Result r = RequirementsService.evaluate(check("java", "21.*", Optional.empty()),
                new MachineProbe.Reading("21.0.8", "", "java on PATH is 21.0.8 but JAVA_HOME points to 17.0.2; Maven uses JAVA_HOME"), TODAY, true);
        assertEquals(Outcome.FAIL, r.outcome());
        assertTrue(r.problem().contains("JAVA_HOME"));
    }

    @Test
    void readsUbuntusReleaseFile() {
        Map<String, String> r = MachineProbe.osRelease("""
                PRETTY_NAME="Ubuntu 24.04.3 LTS"
                NAME="Ubuntu"
                VERSION_ID="24.04"
                ID=ubuntu
                """);
        assertEquals("ubuntu", r.get("ID"));
        assertEquals("24.04", r.get("VERSION_ID"));
        assertEquals("x86_64", MachineProbe.arch("amd64"));
        assertEquals("arm64", MachineProbe.arch("aarch64"));
    }

    @Test
    void javaFromAnotherVendorIsAProblem() {
        CommandRunner runner = c -> new CommandRunner.Result(0, """
                openjdk version "21.0.8" 2025-07-15 LTS
                OpenJDK Runtime Environment Zulu21.44+17-CA (build 21.0.8+9-LTS)""");
        MachineProbe probe = new MachineProbe(runner, Map.of("PATH", "/usr/bin"));
        MachineProbe.Reading r = probe.read("java", Optional.empty(), List.of("Temurin", "OpenJDK"), List.of());
        assertEquals("21.0.8", r.found());
        assertTrue(r.problem().contains("Zulu"), r.problem());
    }

    @Test
    void aDockerDaemonThatIsNotReachableIsExplained() {
        CommandRunner runner = c -> new CommandRunner.Result(1,
                "permission denied while trying to connect to the Docker daemon socket at unix:///var/run/docker.sock");
        MachineProbe.Reading r = new MachineProbe(runner, Map.of()).read("docker", Optional.empty(), List.of(), List.of());
        assertTrue(r.problem().contains("docker group"), r.problem());
    }

    @Test
    void readsTheMavenWrapperOfEachProject() throws Exception {
        Path project = Files.createTempDirectory("kitout-mvnw").resolve("orders");
        Files.createDirectories(project.resolve(".mvn/wrapper"));
        Files.writeString(project.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.8.8/apache-maven-3.8.8-bin.zip\n");
        MachineProbe probe = new MachineProbe(c -> new CommandRunner.Result(-1, ""), Map.of());
        assertEquals(Map.of("orders", "3.8.8"), probe.mavenWrappers(List.of(project)));
    }

    @Test
    void aFingerprintChangesWhenTheToolChanges() throws Exception {
        Path bin = Files.createTempDirectory("kitout-bin");
        Path node = bin.resolve("node");
        Files.writeString(node, "#!/bin/sh\n");
        node.toFile().setExecutable(true);
        MachineProbe probe = new MachineProbe(c -> new CommandRunner.Result(0, ""), Map.of("PATH", bin.toString()));
        String before = probe.fingerprint("node", List.of());
        Files.writeString(node, "#!/bin/sh\necho v22.23.4\n");
        assertTrue(!before.equals(probe.fingerprint("node", List.of())), "a replaced binary is read again");
    }
}
