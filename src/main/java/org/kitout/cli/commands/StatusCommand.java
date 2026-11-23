package org.kitout.cli.commands;

import org.kitout.application.run.ServiceStatus;
import org.kitout.application.run.ServiceSupervisor;
import org.kitout.application.workspace.GroupService;
import org.kitout.cli.Console;
import org.kitout.domain.group.Group;
import org.kitout.domain.run.ServiceHealth;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "status", description = "Show the state, mode and port of every service.")
public class StatusCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"-g", "--group"}, description = "Only this group's services.")
    String group;

    @Inject
    ServiceSupervisor supervisor;

    @Inject
    GroupService groups;

    @Override
    public Integer call() {
        List<ServiceStatus> statuses = supervisor.statuses();
        if (group != null) {
            Group g = groups.require(group);
            statuses = statuses.stream().filter(s -> g.members().contains(s.serviceId())).toList();
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"SERVICE", "STATE", "HEALTH", "MODE", "PORT", "STARTED IN", "DETAIL"});
        statuses.forEach(s -> rows.add(new String[] {s.serviceId(), s.state().name(),
                s.health() == ServiceHealth.UNKNOWN ? "-" : s.health().name(), s.mode().name(), String.valueOf(s.port()),
                s.startupTime().map(ServiceSupervisor::formatSeconds).orElse("-"), s.detail()}));
        Console.table(rows);
        return Console.OK;
    }
}
