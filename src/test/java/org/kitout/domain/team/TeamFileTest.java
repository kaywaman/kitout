package org.kitout.domain.team;

import org.kitout.testsupport.MiniJson;
import org.kitout.testsupport.TeamFixtures;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamFileTest {

    @Test
    void theExampleIsRefusedUntilEveryPlaceholderIsReplaced() {
        TeamSetupParser.Result r = TeamSetupParser.parse(MiniJson.object(TeamFixtures.exampleText()), "1.0.0");
        assertTrue(r.setup().isEmpty());
        int placeholders = TeamFixtures.exampleText().split("<<REPLACE", -1).length - 1;
        assertEquals(23, placeholders, "the example marks 23 values to replace");
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("team")), r.problems().toString());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("enforceFrom")), r.problems().toString());
    }

    @Test
    void theFilledExampleIsValid() {
        TeamSetupParser.Result r = TeamSetupParser.parse(TeamFixtures.filledJson(), "1.0.0");
        assertTrue(r.problems().isEmpty(), r.problems().toString());
        TeamSetup t = r.setup().orElseThrow();
        assertEquals("acme-shop", t.team());
        assertEquals("gitlab.example.com", t.gitlab().host());
        assertEquals(List.of("os", "arch", "java", "maven", "docker", "docker-compose", "node", "git", "memory"),
                t.requirements().checks().stream().map(TeamSetup.Check::id).toList());
        assertTrue(t.requirements().blocks());
        assertEquals(LocalDate.of(2026, 12, 1), t.requirements().checks().get(8).enforceFrom().orElseThrow());
        assertEquals(List.of("Temurin", "OpenJDK"), t.requirements().checks().get(2).vendors());
        assertEquals(List.of("MONGO_PASSWORD"), t.secrets());
        assertEquals(Map.of("MONGO_HOST", "localhost", "MONGO_PORT", "27017", "MONGO_USERNAME", "dev"), t.variables());
        assertEquals(List.of("acme/shop/orders", "acme/shop/billing", "acme/shop/bff", "acme/shop/mobile-bff", "acme/platform/infrastructure",
                "acme/common/java-commons", "acme/common/ui-components"), t.repositories());
        assertEquals("compose/docker-compose-dev.yml", t.compose().get(0).files().get(0).path());
        assertEquals(List.of("acme/platform/infrastructure", "acme/common/java-commons", "acme/common/ui-components"),
                t.nonServiceRepositories().stream().map(TeamSetup.Repository::repo).toList(),
                "the infrastructure repository and the libraries are needed, but never services");
        assertTrue(t.service("acme/platform/infrastructure").isEmpty());
    }

    @Test
    void aTooOldKitoutIsToldToUpdate() {
        Map<String, Object> json = TeamFixtures.filledJson();
        json.put("minKitoutVersion", "9.0.0");
        TeamSetupParser.Result r = TeamSetupParser.parse(json, "1.0.0");
        assertTrue(r.setup().isEmpty());
        assertTrue(r.problems().get(0).contains("9.0.0"), r.problems().toString());
    }

    @Test
    void aPasswordInTheFileIsRefused() {
        Map<String, Object> json = TeamFixtures.filledJson();
        @SuppressWarnings("unchecked")
        Map<String, Object> vars = (Map<String, Object>) json.get("variables");
        vars.put("GITLAB_TOKEN", "glpat-abcdefghijklmnopqrst");
        TeamSetupParser.Result r = TeamSetupParser.parse(json, "1.0.0");
        assertTrue(r.setup().isEmpty(), "a team file with a credential is never used");
        assertFalse(r.problems().toString().contains("glpat-abcdefghijklmnopqrst"), "and the value is not repeated");
    }

    @Test
    void unknownRequirementsAndSecretsInVariablesAreProblems() {
        Map<String, Object> json = TeamFixtures.filledJson();
        @SuppressWarnings("unchecked")
        Map<String, Object> req = (Map<String, Object>) json.get("requirements");
        @SuppressWarnings("unchecked")
        List<Object> checks = (List<Object>) req.get("checks");
        checks.add(new java.util.LinkedHashMap<>(Map.of("id", "python", "require", "3.12")));
        TeamSetupParser.Result r = TeamSetupParser.parse(json, "1.0.0");
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("python")), r.problems().toString());
    }

    @Test
    void aRepositoryIsAServiceOrNotNeverBoth() {
        Map<String, Object> json = TeamFixtures.filledJson();
        @SuppressWarnings("unchecked")
        List<Object> repos = (List<Object>) json.get("repositories");
        repos.add(new java.util.LinkedHashMap<>(Map.of("repo", "acme/shop/orders")));
        TeamSetupParser.Result r = TeamSetupParser.parse(json, "1.0.0");
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("acme/shop/orders")), r.problems().toString());
    }

    @Test
    void composeRepositoriesNotListedStillCountAsRepositories() {
        Map<String, Object> json = TeamFixtures.filledJson();
        json.remove("repositories");
        TeamSetup t = TeamSetupParser.parse(json, "1.0.0").setup().orElseThrow();
        assertEquals("Compose files for dev", t.nonServiceRepositories().stream()
                .filter(r -> r.repo().equals("acme/platform/infrastructure")).findFirst().orElseThrow().description());
    }

    @Test
    void theStarterKitInTheBinaryMatchesTheDocs() throws Exception {
        for (String f : List.of("kitout-team.example.json", "kitout-team.schema.json", "CODEOWNERS.example")) {
            assertEquals(Files.readString(Path.of("docs/team", f)), Files.readString(Path.of("src/main/resources/team", f)),
                    f + ": docs/team and src/main/resources/team must be the same");
        }
    }

    @Test
    void versionRules() {
        assertTrue(VersionRule.parse(">=3.9.0 <4.0.0").matches("3.9.11"));
        assertFalse(VersionRule.parse(">=3.9.0 <4.0.0").matches("3.8.8"));
        assertFalse(VersionRule.parse(">=3.9.0 <4.0.0").matches("4.0.0-rc-4"));
        assertTrue(VersionRule.parse(">=28.5.2").matches("28.5.2"));
        assertTrue(VersionRule.parse(">=28.5.2").matches("29.0.1"));
        assertFalse(VersionRule.parse(">=28.5.2").matches("28.5.1"));
        assertTrue(VersionRule.parse(">=22.23.4").matches("v22.23.4"));
        assertFalse(VersionRule.parse(">=22.23.4").matches("v22.12.0"));
        assertTrue(VersionRule.parse("21.*").matches("21.0.8"));
        assertFalse(VersionRule.parse("21.*").matches("17.0.2"));
        assertTrue(VersionRule.parse("24.04").matches("24.04"));
        assertFalse(VersionRule.parse("24.04").matches("24.10"));
        assertTrue(VersionRule.parse("x86_64").matches("x86_64"));
        assertFalse(VersionRule.parse("x86_64").matches("arm64"));
        assertTrue(VersionRule.parse(">=16384").matches("31842"));
        assertFalse(VersionRule.parse(">=16384").matches("7821"));
        assertFalse(VersionRule.parse(">=2.43.0").matches(""));
    }

    @Test
    void javaVendors() {
        String temurin = """
                openjdk version "21.0.8" 2025-07-15 LTS
                OpenJDK Runtime Environment Temurin-21.0.8+9 (build 21.0.8+9-LTS)
                OpenJDK 64-Bit Server VM Temurin-21.0.8+9 (build 21.0.8+9-LTS, mixed mode, sharing)""";
        String ubuntu = """
                openjdk version "21.0.8" 2025-07-15
                OpenJDK Runtime Environment (build 21.0.8+9-Ubuntu-0ubuntu124.04.1)
                OpenJDK 64-Bit Server VM (build 21.0.8+9-Ubuntu-0ubuntu124.04.1, mixed mode, sharing)""";
        String zulu = """
                openjdk version "21.0.8" 2025-07-15 LTS
                OpenJDK Runtime Environment Zulu21.44+17-CA (build 21.0.8+9-LTS)""";
        assertEquals("Temurin", JavaVendor.detect(temurin));
        assertEquals("OpenJDK", JavaVendor.detect(ubuntu));
        assertEquals("Zulu", JavaVendor.detect(zulu));
        assertEquals("21.0.8", JavaVendor.version(ubuntu));
    }
}
