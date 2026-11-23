package org.kitout.cli;

import org.kitout.application.team.RequirementsService;
import org.kitout.application.team.RequirementsService.Outcome;
import org.kitout.application.team.RequirementsService.Report;
import org.kitout.application.team.RequirementsService.Result;
import org.kitout.application.team.TeamService;

import java.util.Set;

/**
 * Checks the machine against the team's requirements before every CLI command. Commands that build,
 * start or change services stop when a requirement fails; commands that help to fix things (check,
 * doctor, team, env, registry, token, reset, status, logs, help) always run.
 */
public final class RequirementsGate {

    /** Commands that run even when a requirement fails: they inspect or repair, they never build or start. */
    static final Set<String> ALWAYS = Set.of("check", "doctor", "team", "env", "options", "registry", "token", "askpass", "reset", "status", "logs",
            "help", "serve", "generate-completion",
            "down", "stop");   // stopping is always allowed

    private RequirementsGate() {
    }

    /** The command name: the first argument that is not an option; empty for {@code kitout}, {@code kitout --help}. */
    static String command(String... args) {
        for (String a : args) {
            if (!a.startsWith("-")) {
                return a;
            }
        }
        return "";
    }

    /** @return the exit code to stop with, or -1 to carry on */
    static int check(String[] args, TeamService team, RequirementsService requirements) {
        String command = command(args);
        if (command.isEmpty() || command.equals("askpass")) {
            return -1;   // askpass runs inside git: it must stay silent and fast
        }
        Report report;
        try {
            if (!command.equals("serve")) {
                TeamService.foregroundOnly();   // the daemon refreshes in the background; a CLI call ends too soon for that
            }
            team.refreshIfDue();
            report = requirements.check();
        } catch (RuntimeException e) {
            Console.err(Console.warn("!") + " Could not check the team's requirements: " + e.getMessage());
            return -1;
        }
        if (!report.configured()) {
            return -1;
        }
        boolean allowed = ALWAYS.contains(command);
        if (report.blocked() && !allowed) {
            print(report, true);
            Console.err("");
            Console.err("kitout stops here, so nothing is built or started with tools the team does not support.");
            Console.err("Fix the points above, then run: kitout check");
            return Console.REQUIREMENTS;
        }
        if (report.blocked()) {
            Console.err(Console.fail("✗") + " This machine does not meet " + plural(report.failures().size()) + " of the team's requirements."
                    + " Run: kitout check");
        } else if (!report.warnings().isEmpty() && !command.equals("check")) {
            Console.err(Console.warn("!") + " " + report.warnings().size() + " requirement warning" + (report.warnings().size() == 1 ? "" : "s")
                    + ". Run: kitout check");
        }
        return -1;
    }

    /** Prints each requirement that is not met (or every one, with {@code all}) with what was found and how to fix it. */
    public static void print(Report report, boolean onlyProblems) {
        if (!onlyProblems) {
            Console.err("Requirements of the team" + (report.team().isBlank() ? "" : " " + report.team()) + ":");
        } else {
            Console.err(Console.fail("✗") + " This machine does not meet the requirements of the team" + (report.team().isBlank() ? "" : " " + report.team()) + ":");
        }
        for (Result r : report.results()) {
            if (onlyProblems && r.outcome() == Outcome.PASS) {
                continue;
            }
            String symbol = switch (r.outcome()) {
                case PASS -> Console.ok("✓");
                case WARN -> Console.warn("!");
                case FAIL -> Console.fail("✗");
            };
            String found = r.found() == null || r.found().isEmpty() ? "not found" : r.found();
            Console.err("  " + symbol + " " + r.name() + ": " + (r.outcome() == Outcome.PASS ? found : found + ", required " + r.required())
                    + (r.detail() == null || r.detail().isBlank() || r.detail().equals(found) ? "" : "  (" + r.detail() + ")"));
            if (r.outcome() != Outcome.PASS) {
                if (r.problem() != null) {
                    Console.err("      " + r.problem());
                }
                if (r.note() != null) {
                    Console.err("      " + r.note());
                }
                Console.err("      fix: " + r.fix());
            }
        }
    }

    /** The full list on standard output, for {@code kitout team}. */
    public static void printOut(Report report) {
        Console.out("This machine:");
        for (Result r : org.kitout.application.team.RequirementsService.ordered(report.results())) {
            String symbol = switch (r.outcome()) {
                case PASS -> Console.ok("✓");
                case WARN -> Console.warn("!");
                case FAIL -> Console.fail("✗");
            };
            String found = r.found() == null || r.found().isEmpty() ? "not found" : r.found();
            Console.out("  " + symbol + " " + r.name() + ": " + (r.outcome() == Outcome.PASS ? found : found + ", required " + r.required()));
            if (r.outcome() != Outcome.PASS) {
                if (r.problem() != null) {
                    Console.out("      " + r.problem());
                }
                if (r.note() != null) {
                    Console.out("      " + r.note());
                }
                Console.out("      fix: " + r.fix());
            }
        }
    }

    private static String plural(int n) {
        return n == 1 ? "one" : n + "";
    }
}
