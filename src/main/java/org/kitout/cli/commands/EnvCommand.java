package org.kitout.cli.commands;

import org.kitout.application.env.EnvironmentService;
import org.kitout.application.env.EnvironmentService.Change;
import org.kitout.application.env.EnvironmentService.Entry;
import org.kitout.cli.Console;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** {@code kitout env}: environment variables for the services kitout starts, shared or per service. */
@CommandLine.Command(name = "env",
        description = {"Environment variables for the services kitout starts (all modes), shared or per service.",
                "Without a subcommand: list them. Secret values are kept in the keyring and never shown."},
        subcommands = {EnvCommand.Set.class, EnvCommand.Unset.class, EnvCommand.Import.class})
public class EnvCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"-s", "--service"}, description = "The service; without it, the shared variables.")
    String service;

    @Inject
    EnvironmentService environment;

    @Override
    public Integer call() {
        String scope = service == null ? EnvironmentService.SHARED : service;
        List<Entry> entries = environment.list(scope);
        if (entries.isEmpty()) {
            Console.out("No " + (service == null ? "shared variables" : "variables for " + service) + ". Add one with: kitout env set NAME=VALUE"
                    + (service == null ? "" : " --service " + service));
            return Console.OK;
        }
        entries.forEach(e -> Console.out(e.name() + "=" + (e.secret() ? (e.set() ? "*** (secret)" : "(secret, missing: set it again)") : e.value())
                + switch (e.use()) {
                    case RUN -> "";
                    case BUILD -> "   [build only]";
                    case BOTH -> "   [run and build]";
                }));
        return Console.OK;
    }

    @CommandLine.Command(name = "set", description = {"Set NAME=VALUE (a Quarkus property name works too).",
            "With --secret NAME, the value is read from the terminal without echo, or from stdin."})
    public static class Set implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", paramLabel = "NAME=VALUE | NAME")
        String assignment;

        @CommandLine.Option(names = {"-s", "--service"}, description = "The service; without it, a shared variable.")
        String service;

        @CommandLine.Option(names = "--secret", description = "Keep the value in the keyring; it is never shown again.")
        boolean secret;

        @CommandLine.Option(names = "--for", paramLabel = "run|build|both", defaultValue = "run",
                description = "When it applies: when the service runs (default), when kitout builds it, or both.")
        String use;

        @Inject
        EnvironmentService environment;

        @Override
        public Integer call() throws java.io.IOException {
            String scope = service == null ? EnvironmentService.SHARED : service;
            int eq = assignment.indexOf('=');
            String name = eq < 0 ? assignment : assignment.substring(0, eq);
            String value;
            if (secret) {
                if (eq >= 0) {
                    Console.err("Do not put a secret on the command line (it stays in your shell history). Run: kitout env set --secret " + name);
                    return Console.FAILED;
                }
                value = System.console() != null
                        ? new String(System.console().readPassword("Value for %s: ", name))
                        : new java.io.BufferedReader(new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8)).readLine();
            } else {
                if (eq < 0) {
                    Console.err("Write NAME=VALUE, or use --secret NAME for a password");
                    return Console.FAILED;
                }
                value = assignment.substring(eq + 1);
            }
            List<Change> changes = new ArrayList<>();
            for (Entry e : environment.list(scope)) {
                if (!e.name().equals(org.kitout.domain.run.EnvNames.fromProperty(name))) {
                    changes.add(new Change(e.name(), e.value(), e.secret(), e.use()));
                }
            }
            org.kitout.application.config.KitoutConfig.EnvUse when = org.kitout.application.config.KitoutConfig.EnvUse.parse(use);
            changes.add(new Change(name, value, secret, when));
            environment.save(scope, changes);
            Console.out("Set " + org.kitout.domain.run.EnvNames.fromProperty(name) + (service == null ? " for all services" : " for " + service)
                    + switch (when) {
                        case RUN -> ". It applies from the next start.";
                        case BUILD -> ". It applies from the next build.";
                        case BOTH -> ". It applies from the next build and start.";
                    });
            return Console.OK;
        }
    }

    @CommandLine.Command(name = "unset", description = "Remove a variable.")
    public static class Unset implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", paramLabel = "NAME")
        String name;

        @CommandLine.Option(names = {"-s", "--service"}, description = "The service; without it, a shared variable.")
        String service;

        @Inject
        EnvironmentService environment;

        @Override
        public Integer call() {
            String scope = service == null ? EnvironmentService.SHARED : service;
            String target = org.kitout.domain.run.EnvNames.fromProperty(name);
            List<Change> changes = environment.list(scope).stream().filter(e -> !e.name().equals(target))
                    .map(e -> new Change(e.name(), e.value(), e.secret(), e.use())).toList();
            environment.save(scope, changes);
            Console.out("Removed " + target + ".");
            return Console.OK;
        }
    }

    @CommandLine.Command(name = "import", description = {"Take over the variables of a .env file (e.g. the one you use with docker compose).",
            "Passwords, secrets, tokens and keys go to the keyring; values the team file already sets are skipped."})
    public static class Import implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", paramLabel = "FILE")
        java.nio.file.Path file;

        @CommandLine.Option(names = {"-s", "--service"}, description = "The service; without it, shared variables.")
        String service;

        @Inject
        EnvironmentService environment;

        @Override
        public Integer call() throws java.io.IOException {
            EnvironmentService.ImportResult r = environment.importDotEnv(service == null ? EnvironmentService.SHARED : service,
                    java.nio.file.Files.readString(file));
            r.added().forEach(n -> Console.out("+ " + n));
            r.secrets().forEach(n -> Console.out("+ " + n + " (secret, in the keyring)"));
            r.skipped().forEach(n -> Console.out("- " + n));
            Console.out(r.added().size() + r.secrets().size() + " imported, " + r.skipped().size() + " skipped. They apply from the next start.");
            return Console.OK;
        }
    }
}
