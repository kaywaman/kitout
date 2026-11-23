package org.kitout.domain.build;

import org.kitout.domain.project.BuildTool;

import java.util.ArrayList;
import java.util.List;

/**
 * The wrapper command for a build. Profile, mode and skip-tests become flags, never string
 * concatenation into a shell line.
 */
public final class BuildCommandFactory {

    private BuildCommandFactory() {
    }

    /**
     * @param nativeContainerBuild build the native image inside a container, so developers do not
     *                             need GraalVM installed (Docker is a kitout prerequisite anyway)
     */
    public static List<String> command(BuildTool tool, BuildRequest request, boolean nativeContainerBuild) {
        List<String> cmd = new ArrayList<>();
        cmd.add("./" + tool.wrapper());
        switch (tool) {
            case MAVEN -> {
                cmd.add("-B");
                cmd.add("clean");   // always from scratch: a build to run a service, not a dev-mode increment
                cmd.add("package");
                if (request.skipTests()) {
                    cmd.add("-DskipTests");
                }
            }
            case GRADLE -> {
                cmd.add("clean");
                cmd.add("build");
                cmd.add("--console=plain");
                if (request.skipTests()) {
                    cmd.add("-x");
                    cmd.add("test");
                }
            }
            case NPM, NONE -> throw new IllegalArgumentException("kitout builds Java projects only");
        }
        return quarkusFlags(cmd, request, nativeContainerBuild);
    }

    /**
     * The build command for a project of this type. Quarkus takes the build profile
     * ({@code -Dquarkus.profile}) and can build natively; Spring Boot and plain Java are built as they
     * are: their profile is chosen when they run ({@code SPRING_PROFILES_ACTIVE}).
     */
    public static List<String> command(org.kitout.domain.project.ProjectType type, BuildTool tool, BuildRequest request,
                                       boolean nativeContainerBuild) {
        if (!type.buildable() || !tool.java()) {
            throw new IllegalArgumentException("kitout does not build " + type.label() + " projects");
        }
        if (request.mode() == BuildMode.NATIVE && !type.nativeBuild()) {
            throw new IllegalArgumentException("Native builds are for Quarkus projects; build this " + type.label() + " project as JVM");
        }
        if (type != org.kitout.domain.project.ProjectType.QUARKUS) {
            List<String> cmd = new ArrayList<>(List.of("./" + tool.wrapper()));
            return plain(cmd, tool, request);
        }
        return command(tool, request, nativeContainerBuild);
    }

    private static List<String> plain(List<String> cmd, BuildTool tool, BuildRequest request) {
        if (tool == BuildTool.MAVEN) {
            cmd.addAll(List.of("-B", "clean", "package"));
            if (request.skipTests()) {
                cmd.add("-DskipTests");
            }
        } else {
            cmd.addAll(List.of("clean", "build", "--console=plain"));
            if (request.skipTests()) {
                cmd.addAll(List.of("-x", "test"));
            }
        }
        return cmd;
    }

    /** The Quarkus build command. */
    private static List<String> quarkusFlags(List<String> cmd, BuildRequest request, boolean nativeContainerBuild) {
        cmd.add("-Dquarkus.profile=" + request.profile());
        if (request.mode() == BuildMode.NATIVE) {
            cmd.add("-Dquarkus.native.enabled=true");
            if (nativeContainerBuild) {
                cmd.add("-Dquarkus.native.container-build=true");
            }
        }
        return cmd;
    }
}
