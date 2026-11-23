package org.kitout.cli.commands;

import org.kitout.application.KitoutPaths;
import org.kitout.application.run.LogService;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.cli.Console;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "logs", description = "Show a service's log.")
public class LogsCommand implements Callable<Integer> {

    @CommandLine.Parameters(paramLabel = "SERVICE")
    String service;

    @CommandLine.Option(names = {"-n", "--lines"}, defaultValue = "100", description = "Lines to show (default ${DEFAULT-VALUE}).")
    int lines;

    @CommandLine.Option(names = {"-f", "--follow"}, description = "Keep printing new lines until Ctrl+C.")
    boolean follow;

    @Inject
    LogService logs;

    @Inject
    WorkspaceService workspace;

    @Inject
    KitoutPaths paths;

    @Override
    public Integer call() {
        workspace.require(service);
        if (!follow) {
            logs.tail(paths.serviceLog(service), lines).forEach(System.out::println);
            return Console.OK;
        }
        logs.follow(paths.serviceLog(service), lines).subscribe().asStream().forEach(System.out::println);
        return Console.OK;
    }
}
