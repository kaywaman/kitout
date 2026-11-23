package org.kitout.application.doctor.checks;

import org.kitout.application.doctor.DoctorCheck;
import org.kitout.application.doctor.DoctorContext;
import org.kitout.application.run.RunState;
import org.kitout.application.run.StateRepository;
import org.kitout.application.port.NetworkProbe;
import org.kitout.domain.doctor.CheckResult;
import org.kitout.domain.project.Project;
import org.kitout.domain.routing.PortMap;
import org.kitout.domain.run.RunMode;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two members claiming one port (from their files), and ports already taken by a process that
 * kitout did not start and that is not the member's IDE run.
 */
@ApplicationScoped
public class PortConflictCheck implements DoctorCheck {

    private final NetworkProbe network;
    private final StateRepository state;

    public PortConflictCheck(NetworkProbe network, StateRepository state) {
        this.network = network;
        this.state = state;
    }

    @Override
    public String id() {
        return "ports";
    }

    @Override
    public int order() {
        return 95;
    }

    @Override
    public boolean appliesTo(DoctorContext context) {
        return !context.members().isEmpty();
    }

    @Override
    public List<CheckResult> run(DoctorContext context) {
        List<CheckResult> results = new ArrayList<>();
        Map<String, Integer> ports = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        context.members().stream().filter(m -> m.devPort() > 0).forEach(m -> {   // a Java app without a port claims none
            ports.put(m.id(), m.devPort());
            names.put(m.id(), m.name());
        });
        new PortMap(ports).conflicts().forEach((port, ids) -> results.add(CheckResult.error(id(), "Port " + port,
                "Claimed by " + String.join(" and ", ids.stream().map(names::get).toList()),
                "Change the port in one of them (%dev.quarkus.http.port, server.port, the dev server's port), or set another under Options").asAdvisory()));

        RunState runs = state.load();
        for (Project m : context.members()) {
            boolean ours = runs.run(m.id()).isPresent() || context.runMode(m) == RunMode.IDE;
            if (!ours && m.devPort() > 0 && network.canConnect("localhost", m.devPort(), Duration.ofMillis(300))) {
                results.add(CheckResult.warning(id(), "Port " + m.devPort(), m.name() + "'s port is taken by "
                                + network.portHolder(m.devPort()).orElse("another process"),
                        "Stop that process, or set " + m.name() + " to run in your IDE if that is what holds the port"));
            }
        }
        if (results.isEmpty()) {
            results.add(CheckResult.ok(id(), "Ports", "No conflicts among " + ports.size() + " services"));
        }
        return results;
    }
}
