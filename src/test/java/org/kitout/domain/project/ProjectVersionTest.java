package org.kitout.domain.project;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectVersionTest {

    @Test
    void readsTheProjectsOwnVersionNotADependencysOrThePluginsOrTheParents() {
        String pom = """
                <project>
                  <parent><groupId>acme</groupId><artifactId>parent</artifactId><version>7</version></parent>
                  <artifactId>orders</artifactId>
                  <version>1.4.0-SNAPSHOT</version>
                  <properties><quarkus.version>3.40.1</quarkus.version></properties>
                  <dependencies><dependency><artifactId>x</artifactId><version>9.9</version></dependency></dependencies>
                </project>""";
        assertEquals(Optional.of("1.4.0-SNAPSHOT"), ProjectVersion.fromPom(pom));
    }

    @Test
    void aModuleInheritsTheParentsVersionAndPropertiesAreResolved() {
        assertEquals(Optional.of("2.1.0"), ProjectVersion.fromPom(
                "<project><parent><version>2.1.0</version></parent><artifactId>orders</artifactId></project>"));
        assertEquals(Optional.of("3.0.0-rc1"), ProjectVersion.fromPom(
                "<project><version>${revision}</version><properties><revision>3.0.0-rc1</revision></properties></project>"));
        assertEquals(Optional.empty(), ProjectVersion.fromPom("<project><version>${revision}</version></project>"));
    }

    @Test
    void readsGradleVersions() {
        assertEquals(Optional.of("1.2.3"), ProjectVersion.fromGradle("plugins {}\nversion = \"1.2.3\"\n", ""));
        assertEquals(Optional.of("0.9.0"), ProjectVersion.fromGradle("plugins {}", "version=0.9.0\n"));
        assertEquals(Optional.empty(), ProjectVersion.fromGradle("", ""));
    }
}
