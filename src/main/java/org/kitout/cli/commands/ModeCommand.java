package org.kitout.cli.commands;

import org.kitout.application.workspace.GroupService;
import org.kitout.application.workspace.ServiceAdminService;
import org.kitout.cli.Console;
import org.kitout.domain.run.RunMode;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "mode",
        description = {"Set how services start: IMAGE, JVM, NATIVE (Quarkus), SCRIPT (Node: npm run) or IDE.",
                "For all services, a group, or the ones named. Services whose type has no such mode keep theirs."})
public class ModeCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", paramLabel = "MODE")
    RunMode mode;

    @CommandLine.Parameters(index = "1..*", arity = "0..*", paramLabel = "SERVICE")
    List<String> services = List.of();

    @CommandLine.Option(names = {"-g", "--group"}, description = "Only this group's services.")
    String group;

    @Inject
    ServiceAdminService admin;

    @Inject
    GroupService groups;

    @Override
    public Integer call() {
        List<String> targets = group != null ? groups.require(group).members() : services;
        ServiceAdminService.RunModeResult r = admin.setRunMode(mode, targets);
        Console.out(r.changed() + " services now start as " + mode + "."
                + (r.running() > 0 ? " " + r.running() + " running services keep their process until restarted." : ""));
        r.skipped().forEach(s -> Console.out("  kept: " + s));
        return Console.OK;
    }
}
