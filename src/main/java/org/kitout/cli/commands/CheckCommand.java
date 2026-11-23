package org.kitout.cli.commands;

import org.kitout.application.team.RequirementsService;
import org.kitout.application.team.RequirementsService.Report;
import org.kitout.cli.Console;
import org.kitout.cli.RequirementsGate;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/** {@code kitout check}: the team's machine requirements, what this machine has, how to fix each gap. */
@CommandLine.Command(name = "check",
        description = {"Check this machine against the team's requirements (OS, Java, Maven, Docker, Node.js, ...).",
                "kitout does this before every command; this shows every requirement and how to fix the ones that fail."})
public class CheckCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--force", description = "Read every tool again instead of using readings kept while the tools were unchanged.")
    boolean force;

    @Inject
    RequirementsService requirements;

    @Override
    public Integer call() {
        Report report = requirements.check(force);
        if (!report.configured()) {
            Console.out("No team file is in use, so there are no requirements to check. Set one with: kitout team use <location>");
            return Console.OK;
        }
        if (report.results().isEmpty()) {
            Console.out("The team file sets no requirements.");
            return Console.OK;
        }
        RequirementsGate.print(report, false);
        Console.err("");
        if (report.blocked()) {
            Console.err(report.failures().size() + " not met: kitout builds and starts nothing until they are fixed.");
            return Console.REQUIREMENTS;
        }
        Console.err(report.warnings().isEmpty() ? "All requirements are met." : "All required checks pass; " + report.warnings().size() + " warning(s).");
        return Console.OK;
    }
}
