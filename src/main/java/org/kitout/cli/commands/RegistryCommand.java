package org.kitout.cli.commands;

import org.kitout.application.registry.RegistryService;
import org.kitout.application.registry.RegistryService.Status;
import org.kitout.cli.Console;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

/** {@code kitout registry}: the docker login for the container registry in Settings. */
@CommandLine.Command(name = "registry",
        description = {"Show the login for the container registry; 'login' and 'logout' change it.",
                "Docker keeps the login (as with 'docker login'); kitout stores no copy."},
        subcommands = {RegistryCommand.Login.class, RegistryCommand.Logout.class})
public class RegistryCommand implements Callable<Integer> {

    @Inject
    RegistryService registry;

    @Override
    public Integer call() {
        print(registry.status());
        return Console.OK;
    }

    static void print(Status s) {
        if (s.host().isEmpty()) {
            Console.out("No container registry set. Set it in Settings (UI).");
        } else {
            Console.out(s.host() + ": " + (s.loggedIn() ? "logged in as " + s.username() : "not logged in. Run: kitout registry login --username <user>"));
        }
    }

    @CommandLine.Command(name = "login", description = "docker login to the registry. The password or token is read from the terminal "
            + "without echo, or from stdin with --password-stdin; never pass it as an argument.")
    public static class Login implements Callable<Integer> {

        @CommandLine.Option(names = {"-u", "--username"}, required = true, description = "Your registry user (may differ from your git user).")
        String username;

        @CommandLine.Option(names = "--password-stdin", description = "Read the password or token from stdin.")
        boolean stdin;

        @Inject
        RegistryService registry;

        @Override
        public Integer call() throws IOException {
            String secret;
            if (stdin || System.console() == null) {
                secret = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            } else {
                char[] typed = System.console().readPassword("Password or token for %s: ", username);
                secret = typed == null ? null : new String(typed);
            }
            print(registry.login(username, secret));
            return Console.OK;
        }
    }

    @CommandLine.Command(name = "logout", description = "docker logout from the registry.")
    public static class Logout implements Callable<Integer> {

        @Inject
        RegistryService registry;

        @Override
        public Integer call() {
            print(registry.logout());
            return Console.OK;
        }
    }
}
