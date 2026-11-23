package org.kitout.cli.commands;

import org.kitout.application.run.GroupRunner;
import org.kitout.cli.Console;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "down", description = "Stop a group: the BFF first, then the services.")
public class DownCommand implements Callable<Integer> {

    @CommandLine.Parameters(paramLabel = "GROUP")
    String group;

    @Inject
    GroupRunner runner;

    @Override
    public Integer call() {
        runner.stop(group, Console::out);
        return Console.OK;
    }
}
