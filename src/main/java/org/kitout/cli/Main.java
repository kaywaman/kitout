package org.kitout.cli;

import org.kitout.application.KitoutPaths;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point for both faces of kitout.
 *
 * <p>{@code kitout} or {@code kitout serve} starts the daemon with the web UI. Every other command
 * runs the same core in-process and exits; for those the HTTP server stays off (so the CLI
 * works next to a running daemon) and logging goes to stderr at WARN, so stdout carries only
 * the command's own output (important for {@code kitout askpass}, which git reads).
 *
 * <p>kitout's own log goes to {@code ~/.local/state/kitout/kitout.log} (next to the service logs, and
 * moved along with {@code XDG_STATE_HOME} or {@code KITOUT_HOME}), in addition to the terminal.
 */
@QuarkusMain
public final class Main {

    public static void main(String... args) {
        useLogFile(new KitoutPaths().kitoutLog());
        boolean serve = args.length == 0 || args[0].equals("serve");
        if (!serve) {
            System.setProperty("quarkus.http.host-enabled", "false");
            System.setProperty("quarkus.log.console.stderr", "true");
            System.setProperty("quarkus.log.level", "WARN");
            System.setProperty("quarkus.banner.enabled", "false");
        }
        Quarkus.run(KitoutApplication.class, args.length == 0 ? new String[] {"serve"} : args);
    }

    private static void useLogFile(Path file) {
        try {
            Files.createDirectories(file.getParent());
            System.setProperty("quarkus.log.file.path", file.toString());
        } catch (IOException e) {
            // no file log: the terminal still shows everything
            System.setProperty("quarkus.log.file.enabled", "false");
        }
    }
}
