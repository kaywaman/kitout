package org.kitout.domain.project;

import java.util.List;

/**
 * How a project is built. Java projects always through their wrapper, so every developer uses the
 * same tool version. Detected in this order: a folder with a pom.xml is a Maven project even when it
 * also has a package.json (a frontend inside it).
 */
public enum BuildTool {
    MAVEN("mvnw", List.of("pom.xml")),
    GRADLE("gradlew", List.of("build.gradle.kts", "build.gradle")),
    /** A Node project: npm, as installed on the machine (there is no wrapper). */
    NPM("npm", List.of("package.json")),
    /** An image-only service: nothing to build. */
    NONE("", List.of());

    private final String wrapper;
    private final List<String> buildFiles;

    BuildTool(String wrapper, List<String> buildFiles) {
        this.wrapper = wrapper;
        this.buildFiles = buildFiles;
    }

    public String wrapper() {
        return wrapper;
    }

    /** A Java build tool with a wrapper in the project ({@code ./mvnw}, {@code ./gradlew}). */
    public boolean java() {
        return this == MAVEN || this == GRADLE;
    }

    public List<String> buildFiles() {
        return buildFiles;
    }
}
