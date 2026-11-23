package org.kitout.cli.commands;

import org.kitout.application.reset.ResetService;
import org.kitout.application.reset.ResetService.Options;
import org.kitout.application.reset.ResetService.Preview;
import org.kitout.cli.Console;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "reset",
        description = {"Start kitout over. Stops what kitout runs, then removes the chosen parts (all but --build-output by default).",
                "Never touched: your repositories, compose files and their data, your docker and git logins."})
public class ResetCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--settings", description = "Settings, run state, kitout's secrets in the keyring.")
    boolean settings;

    @CommandLine.Option(names = "--logs", description = "Service, compose and kitout logs.")
    boolean logs;

    @CommandLine.Option(names = "--builds", description = "The build list and build logs.")
    boolean builds;

    @CommandLine.Option(names = "--images", description = "The service images kitout pulled, leftover kitout containers.")
    boolean images;

    @CommandLine.Option(names = "--build-output", description = "Also target/ or build/ inside each project.")
    boolean buildOutput;

    @CommandLine.Option(names = "--yes", description = "Do it. Without --yes, only show what would be removed.")
    boolean yes;

    @Inject
    ResetService reset;

    @Override
    public Integer call() {
        boolean chosen = settings || logs || builds || images;
        Options options = chosen ? new Options(settings, logs, builds, buildOutput, images) : new Options(true, true, true, buildOutput, true);
        Preview p = reset.preview();
        Console.out("Would " + (p.runningServices().isEmpty() ? "" : "stop " + p.runningServices().size() + " service(s), then ") + "remove:");
        if (options.settings()) {
            Console.out("  settings " + p.configFile() + ", run state, " + p.secrets() + " secret(s) in the keyring");
        }
        if (options.logs()) {
            Console.out("  " + p.logFiles() + " log file(s), " + (p.logBytes() / 1024 / 1024) + " MB");
        }
        if (options.builds()) {
            Console.out("  " + p.builds() + " build(s) in the list, " + p.buildLogs() + " build log(s)");
        }
        if (options.images()) {
            Console.out("  " + p.images().size() + " service image(s)" + (p.images().isEmpty() ? "" : ": " + String.join(", ", p.images())));
        }
        if (options.buildOutput()) {
            Console.out("  build output: " + String.join(", ", p.buildOutputs()));
        }
        if (!yes) {
            Console.out("Nothing changed. Run again with --yes to do it.");
            return Console.OK;
        }
        ResetService.Outcome o = reset.reset(options);
        o.done().forEach(line -> Console.out("Done: " + line));
        o.problems().forEach(line -> Console.err("Not done: " + line));
        return o.problems().isEmpty() ? Console.OK : Console.FAILED;
    }
}
