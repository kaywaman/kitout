package org.kitout.cli.commands;

import org.kitout.application.workspace.ServiceAdminService;
import org.kitout.cli.Console;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "remove",
        description = {"Remove a service from kitout (files stay on disk; scans skip it), or all with --all.",
                "A service kitout runs must be stopped first ('kitout stop SERVICE' or 'kitout down GROUP')."})
public class RemoveCommand implements Callable<Integer> {

    @CommandLine.Parameters(arity = "0..1", paramLabel = "SERVICE")
    String service;

    @CommandLine.Option(names = "--all", description = "Remove every service, group and workspace folder.")
    boolean all;

    @CommandLine.Option(names = "--yes", description = "Confirm --all.")
    boolean yes;

    @Inject
    ServiceAdminService admin;

    @Override
    public Integer call() {
        if (all) {
            if (!yes) {
                Console.err("This forgets all services, groups and workspace folders. "
                        + "Files on disk stay. Run again with --all --yes to do it.");
                return Console.FAILED;
            }
            ServiceAdminService.RemoveAllResult r = admin.removeAll();
            Console.out("Removed " + r.removed() + " services.");
            return Console.OK;
        }
        if (service == null) {
            Console.err("Name a service (see 'kitout status'), or use --all.");
            return Console.FAILED;
        }
        admin.remove(service);
        Console.out("Removed " + service + ". Its folder is skipped by future scans; files stay on disk.");
        return Console.OK;
    }
}
