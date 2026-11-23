package org.kitout.application.run;

import org.kitout.domain.project.BuildTool;
import org.kitout.domain.project.Project;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/** Finds the local build output of a project: the JVM run jar (per project type) or the native runner. */
@ApplicationScoped
public class ArtifactLocator {

    public Optional<Path> jvmRunJar(Project project) {
        Path jar = outputDir(project).resolve("quarkus-app").resolve("quarkus-run.jar");
        return Files.isRegularFile(jar) ? Optional.of(jar) : Optional.empty();
    }

    /**
     * What a JVM start runs: Quarkus' {@code quarkus-app/quarkus-run.jar}, or for Spring Boot and plain
     * Java the runnable jar in {@code target/} ({@code build/libs/} with Gradle).
     */
    public Optional<Path> jvmArtifact(Project project) {
        return switch (project.type()) {
            case QUARKUS -> jvmRunJar(project);
            case SPRING_BOOT, JAVA -> runnableJar(project);
            default -> Optional.empty();
        };
    }

    /**
     * The newest jar with a {@code Main-Class} in its manifest: Spring Boot's repackaged jar, or a
     * plain Java app's shaded or assembled one. Sources, javadoc, test and {@code -plain} jars, and the
     * {@code .original} Spring Boot keeps, are not runnable and are skipped.
     */
    public Optional<Path> runnableJar(Project project) {
        Path dir = project.buildTool() == BuildTool.GRADLE ? project.path().resolve("build").resolve("libs") : outputDir(project);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".jar") && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar")
                                && !n.endsWith("-tests.jar") && !n.endsWith("-plain.jar") && !n.startsWith("original-");
                    })
                    .filter(ArtifactLocator::hasMainClass)
                    .max(Comparator.comparing(ArtifactLocator::modified));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static boolean hasMainClass(Path jar) {
        try (java.util.jar.JarFile file = new java.util.jar.JarFile(jar.toFile())) {
            java.util.jar.Manifest m = file.getManifest();
            return m != null && m.getMainAttributes().getValue("Main-Class") != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** The newest executable {@code *-runner} in the build output folder. */
    public Optional<Path> nativeRunner(Project project) {
        Path dir = outputDir(project);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith("-runner"))
                    .filter(Files::isExecutable)
                    .max(Comparator.comparing(ArtifactLocator::modified));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static Path outputDir(Project project) {
        return project.path().resolve(project.buildTool() == BuildTool.GRADLE ? "build" : "target");
    }

    private static FileTime modified(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }
}
