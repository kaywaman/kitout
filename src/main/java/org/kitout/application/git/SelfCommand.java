package org.kitout.application.git;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

/**
 * How to run this kitout installation again, e.g. for the askpass helper script:
 * the native executable itself, or {@code java -jar .../quarkus-run.jar} in JVM mode.
 * Set the {@code KITOUT_SELF} environment variable to override (one path, no arguments).
 */
@ApplicationScoped
public class SelfCommand {

    public List<String> command() {
        String override = System.getenv("KITOUT_SELF");
        if (override != null && !override.isBlank()) {
            return List.of(override);
        }
        ProcessHandle.Info info = ProcessHandle.current().info();
        String executable = info.command().orElse("");
        if (executable.isEmpty()) {
            return List.of();
        }
        if (isNativeExecutable()) {
            return List.of(executable);
        }
        String[] args = info.arguments().orElse(new String[0]);
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("-jar")) {
                List<String> cmd = new ArrayList<>();
                cmd.add(executable);
                cmd.add("-jar");
                cmd.add(args[i + 1]);
                return cmd;
            }
        }
        return List.of();
    }

    /**
     * True inside a native executable. Native images set this property to {@code runtime};
     * reading it avoids a compile-time dependency on the GraalVM SDK, so JVM builds and tests
     * need no GraalVM.
     */
    static boolean isNativeExecutable() {
        return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
    }
}
