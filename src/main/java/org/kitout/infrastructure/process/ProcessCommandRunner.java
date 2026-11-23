package org.kitout.infrastructure.process;

import org.kitout.application.port.CommandRunner;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@link CommandRunner} on {@link ProcessBuilder}. No shell is involved: the argument list is
 * passed to the operating system as is.
 */
@ApplicationScoped
public class ProcessCommandRunner implements CommandRunner {

    private static final int MAX_OUTPUT = 2 * 1024 * 1024;

    @Override
    public Result run(Command command) {
        ProcessBuilder pb = new ProcessBuilder(command.args()).redirectErrorStream(true);
        command.workingDirectory().ifPresent(dir -> pb.directory(dir.toFile()));
        Map<String, String> env = pb.environment();
        command.removeEnvironment().forEach(env::remove);   // removals first, so explicit values win
        env.putAll(command.environment());
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new Result(-1, e.getMessage() == null ? "" : e.getMessage());
        }
        try {
            writeStdin(process, command.stdin().orElse(null));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> copy(process.getInputStream(), out));
            boolean done = process.waitFor(command.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!done) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                reader.join(1000);
                return new Result(-2, out.toString(StandardCharsets.UTF_8));
            }
            reader.join(5000);
            return new Result(process.exitValue(), out.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result(-2, "interrupted");
        }
    }

    private static void writeStdin(Process process, String input) {
        try (OutputStream stdin = process.getOutputStream()) {
            if (input != null) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // the command does not read stdin; fine
        }
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        byte[] buffer = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (out.size() < MAX_OUTPUT) {
                    out.write(buffer, 0, Math.min(n, MAX_OUTPUT - out.size()));
                }
            }
        } catch (IOException e) {
            // process ended
        }
    }
}
