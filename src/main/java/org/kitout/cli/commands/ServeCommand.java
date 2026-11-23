package org.kitout.cli.commands;

import org.kitout.api.security.SessionToken;
import org.kitout.application.port.CommandRunner;
import org.kitout.application.port.CommandRunner.Command;
import io.quarkus.runtime.Quarkus;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/** {@code kitout serve}: the daemon with the web UI. Prints the link that carries the session token. */
@CommandLine.Command(name = "serve", description = "Start kitout with the web UI (the default without arguments).")
public class ServeCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--open", description = "Open the UI in the default browser.")
    boolean open;

    @Inject
    SessionToken token;

    @Inject
    CommandRunner runner;

    @Inject
    org.kitout.application.team.SetupService setup;

    @Inject
    org.kitout.application.docker.ComposeService compose;

    /** The same check the UI shows on start: one line when all is well, otherwise what is open. */
    private void startupCheck() {
        try {
            var s = setup.status(false);
            if (s.ready() && compose.teamEnvironments().stream().anyMatch(e -> e.autoStart())) {
                System.out.println("  Starting the team's compose environments in the background (progress on the Docker page).");
                Thread.ofVirtual().name("compose-autostart").start(() -> {
                    try {
                        compose.ensureTeamEnvironments();
                    } catch (RuntimeException e) {
                        System.out.println("  " + org.kitout.cli.Console.warn("!") + " " + org.kitout.domain.identity.Redactor.redact(String.valueOf(e.getMessage())));
                    }
                });
            }
            if (s.complete()) {
                long services = s.projects().stream().filter(p -> p.kind().equals("service")).count();
                System.out.println("  " + org.kitout.cli.Console.ok("✓") + " All checks passed for " + s.team().setup().map(t -> t.team()).orElse("your team")
                        + ": team file, " + s.requirements().results().size() + " machine requirements, " + services + " services.");
            } else if (!s.team().configured()) {
                System.out.println("  No team file yet: the Setup page asks for it (or: kitout team use <location>).");
            } else {
                System.out.println("  " + org.kitout.cli.Console.warn("!") + " Setup is not complete: the UI opens Setup with what to do (or: kitout team).");
            }
            System.out.println();
        } catch (RuntimeException e) {
            System.out.println("  Could not run the start-up check: " + org.kitout.domain.identity.Redactor.redact(String.valueOf(e.getMessage())));
            System.out.println();
        }
    }

    @ConfigProperty(name = "quarkus.http.port")
    int port;

    @Override
    public Integer call() {
        String url = "http://127.0.0.1:" + port + "/#token=" + token.value();
        System.out.println();
        System.out.println("  kitout is running. Open it with this link once per browser (it holds your key; afterwards");
        System.out.println("  http://127.0.0.1:" + port + " is enough):");
        System.out.println();
        System.out.println("    " + url);
        System.out.println();
        startupCheck();
        System.out.println("  Stop kitout with Ctrl+C. Running services keep running and are picked up again next time.");
        System.out.println();
        if (open) {
            runner.run(Command.of("xdg-open", url));
        }
        Quarkus.waitForExit();
        return 0;
    }
}
