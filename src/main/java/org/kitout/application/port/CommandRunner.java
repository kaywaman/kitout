package org.kitout.application.port;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs a short-lived command and returns its output. Commands are argument lists, never shell
 * strings, so user input can never change what is executed.
 */
public interface CommandRunner {

    record Command(List<String> args, Optional<Path> workingDirectory, Map<String, String> environment,
                   List<String> removeEnvironment, Optional<String> stdin, Duration timeout) {

        public Command {
            args = List.copyOf(args);
            environment = Map.copyOf(environment);
            removeEnvironment = List.copyOf(removeEnvironment);
        }

        public static Command of(String... args) {
            return new Command(List.of(args), Optional.empty(), Map.of(), List.of(), Optional.empty(), Duration.ofSeconds(15));
        }

        public static Command of(List<String> args) {
            return new Command(args, Optional.empty(), Map.of(), List.of(), Optional.empty(), Duration.ofSeconds(15));
        }

        public Command in(Path dir) {
            return new Command(args, Optional.of(dir), environment, removeEnvironment, stdin, timeout);
        }

        public Command withEnv(Map<String, String> env) {
            return new Command(args, workingDirectory, env, removeEnvironment, stdin, timeout);
        }

        public Command withoutEnv(List<String> names) {
            return new Command(args, workingDirectory, environment, names, stdin, timeout);
        }

        public Command withStdin(String input) {
            return new Command(args, workingDirectory, environment, removeEnvironment, Optional.of(input), timeout);
        }

        public Command withTimeout(Duration t) {
            return new Command(args, workingDirectory, environment, removeEnvironment, stdin, t);
        }
    }

    /**
     * @param exitCode -1 if the command could not be started, -2 if it timed out
     * @param output   stdout and stderr combined
     */
    record Result(int exitCode, String output) {

        public boolean succeeded() {
            return exitCode == 0;
        }

        public boolean notFound() {
            return exitCode == -1;
        }

        public boolean timedOut() {
            return exitCode == -2;
        }

        public String firstLine() {
            String trimmed = output.strip();
            int nl = trimmed.indexOf('\n');
            return nl < 0 ? trimmed : trimmed.substring(0, nl);
        }
    }

    Result run(Command command);
}
