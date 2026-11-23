package org.kitout.domain.run;

import org.kitout.domain.project.Project;
import org.kitout.domain.project.ProjectType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns "run this service in this mode" into a {@link LaunchSpec}. Pure: the caller finds the
 * artifacts on disk, this class only decides the command line.
 *
 * <p>Every mode runs with the same runtime profile (default {@code dev}), so {@code localhost}
 * URLs and ports mean the same for an image, a local build and the IDE-run service.
 */
public final class LaunchPlanner {

    /** The label on every container kitout starts: its value is the service id. */
    public static final String LABEL = "org.kitout.service";

    private LaunchPlanner() {
    }

    /**
     * @param env the developer's variables. Passed as {@code -e NAME} without the value: docker takes
     *            the value from its own environment, so values (e.g. a database password) never show
     *            up in the process list or in kitout's logs.
     */
    public static LaunchSpec image(Project project, String image, String runtimeProfile, Map<String, String> env) {
        return image(project, image, runtimeProfile, env, List.of(), List.of());
    }

    /**
     * @param mounts    {@code --mount} values for host folders ({@link DockerOptions#mount})
     * @param extraArgs the developer's own options, already checked by {@link DockerOptions#parse}; placed after
     *                  kitout's options and before the image, so they cannot replace the image or its command
     */
    public static LaunchSpec image(Project project, String image, String runtimeProfile, Map<String, String> env,
                                   List<String> mounts, List<String> extraArgs) {
        String container = containerName(project.id());
        List<String> cmd = new ArrayList<>(List.of(
                "docker", "run", "--rm",
                "--name", container,
                "--network", "host",
                "--label", LABEL + "=" + project.id()));
        profileVariable(project.type(), runtimeProfile).ifPresent(v -> {
            cmd.add("-e");
            cmd.add(v);
        });
        env.keySet().forEach(name -> {
            cmd.add("-e");
            cmd.add(name);
        });
        mounts.forEach(m -> {
            cmd.add("--mount");
            cmd.add(m);
        });
        cmd.addAll(extraArgs);
        cmd.add(image);
        return new LaunchSpec(cmd, project.path(), env, Optional.of(container), Optional.of(image));
    }

    /**
     * How the runtime profile reaches a container: {@code QUARKUS_PROFILE} or {@code SPRING_PROFILES_ACTIVE}.
     * Plain Java, Node and image-only services get none: kitout does not know what they read.
     */
    static Optional<String> profileVariable(ProjectType type, String profile) {
        return switch (type) {
            case QUARKUS -> Optional.of("QUARKUS_PROFILE=" + profile);
            case SPRING_BOOT -> Optional.of("SPRING_PROFILES_ACTIVE=" + profile);
            default -> Optional.empty();
        };
    }

    /**
     * @param runJar Quarkus' {@code quarkus-app/quarkus-run.jar}, or the runnable jar of a Spring Boot or plain Java project
     */
    public static LaunchSpec jvm(Project project, Path runJar, String runtimeProfile, List<String> extraJvmArgs,
                                 Map<String, String> env) {
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.addAll(extraJvmArgs);
        switch (project.type()) {
            case QUARKUS -> cmd.add("-Dquarkus.profile=" + runtimeProfile);
            case SPRING_BOOT -> cmd.add("-Dspring.profiles.active=" + runtimeProfile);
            default -> {
                // a plain Java app: no profile kitout knows of
            }
        }
        cmd.add("-jar");
        cmd.add(runJar.toString());
        return new LaunchSpec(cmd, project.path(), env, Optional.empty(), Optional.empty());
    }

    /** @param runner the native executable, e.g. {@code target/orders-1.0.0-runner} */
    public static LaunchSpec nativeRunner(Project project, Path runner, String runtimeProfile, Map<String, String> env) {
        List<String> cmd = List.of(runner.toString(), "-Dquarkus.profile=" + runtimeProfile);
        return new LaunchSpec(cmd, project.path(), env, Optional.empty(), Optional.empty());
    }

    /**
     * A Node project: {@code npm run <script>} in its folder. {@code PORT} is set to the port kitout
     * watches, which many dev servers (Next.js, Create React App, Express apps) take; others read their own config.
     */
    public static LaunchSpec script(Project project, String script, int port, Map<String, String> env) {
        Map<String, String> withPort = new java.util.LinkedHashMap<>(env);
        if (port > 0) {
            withPort.putIfAbsent("PORT", String.valueOf(port));
        }
        return new LaunchSpec(List.of("npm", "run", script), project.path(), withPort, Optional.empty(), Optional.empty());
    }

    public static String containerName(String serviceId) {
        return "kitout-" + serviceId.replaceAll("[^a-zA-Z0-9_.-]", "-");
    }
}
