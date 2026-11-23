package org.kitout.cli.commands;

import org.kitout.application.run.GroupRunner;
import org.kitout.application.run.GroupRunner.BlockedByDoctorException;
import org.kitout.cli.Console;
import org.kitout.domain.doctor.Severity;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "up", description = "Start a group: services first, then the BFF once they are healthy.")
public class UpCommand implements Callable<Integer> {

    @CommandLine.Parameters(paramLabel = "GROUP")
    String group;

    @CommandLine.Option(names = "--force", description = "Start even if the doctor finds errors.")
    boolean force;

    @Inject
    GroupRunner runner;

    @Override
    public Integer call() {
        try {
            GroupRunner.Progress progress = runner.startAndWait(group, force, Console::out);
            return progress.messages().stream().anyMatch(m -> m.startsWith("Warning")) ? Console.FAILED : Console.OK;
        } catch (BlockedByDoctorException e) {
            e.report().results().stream().filter(r -> r.severity() == Severity.ERROR).forEach(Console::check);
            Console.err("Not started: the doctor found errors. Fix them, or use --force.");
            return Console.BLOCKED;
        }
    }
}
