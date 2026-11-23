package org.kitout.cli;

import org.kitout.application.team.RequirementsService;
import org.kitout.application.team.TeamService;
import io.quarkus.runtime.QuarkusApplication;
import jakarta.inject.Inject;
import picocli.CommandLine;

/** Hands the arguments to picocli; the factory lets commands use CDI injection. */
public class KitoutApplication implements QuarkusApplication {

    @Inject
    CommandLine.IFactory factory;

    @Inject
    TeamService team;

    @Inject
    RequirementsService requirements;

    @Override
    public int run(String... args) {
        // every call checks the machine against the team's requirements (fast while nothing changed)
        int stop = RequirementsGate.check(args, team, requirements);
        if (stop >= 0) {
            return stop;
        }
        return new CommandLine(KitoutCommand.class, factory)
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler((e, cmd, parseResult) -> {
                    // expected problems (unknown service, port taken, ...) get one clear line, not a stack trace
                    Console.err(e.getMessage() == null ? e.toString() : e.getMessage());
                    return Console.FAILED;
                })
                .execute(args);
    }
}
