package org.kitout.application.doctor.checks;

import org.kitout.application.doctor.DoctorCheck;
import org.kitout.application.doctor.DoctorContext;
import org.kitout.application.port.NetworkProbe;
import org.kitout.domain.config.Endpoint;
import org.kitout.domain.doctor.CheckResult;
import org.kitout.domain.project.Project;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The databases and brokers the services declare are reachable. kitout never creates them:
 * the developer sets them up, kitout only connects to the port.
 */
@ApplicationScoped
public class InfrastructureCheck implements DoctorCheck {

    private final NetworkProbe network;

    public InfrastructureCheck(NetworkProbe network) {
        this.network = network;
    }

    @Override
    public String id() {
        return "infrastructure";
    }

    @Override
    public int order() {
        return 90;
    }

    @Override
    public boolean appliesTo(DoctorContext context) {
        return context.members().stream().anyMatch(m -> !m.infrastructure().isEmpty());
    }

    @Override
    public List<CheckResult> run(DoctorContext context) {
        Map<String, List<String>> usersByAddress = new LinkedHashMap<>();
        Map<String, Endpoint> endpoints = new LinkedHashMap<>();
        for (Project m : context.members()) {
            for (Endpoint e : m.infrastructure()) {
                usersByAddress.computeIfAbsent(e.address(), k -> new ArrayList<>()).add(m.name());
                endpoints.putIfAbsent(e.address(), e);
            }
        }
        List<CheckResult> results = new ArrayList<>();
        endpoints.forEach((address, e) -> {
            String title = e.kind() + " " + address;
            String users = String.join(", ", usersByAddress.get(address));
            results.add((network.canConnect(e.host(), e.port(), Duration.ofSeconds(2))
                    ? CheckResult.ok(id(), title, "Reachable (used by " + users + ")")
                    : CheckResult.error(id(), title, "Not reachable; needed by " + users + " (" + e.source() + ")",
                    "Start the " + e.kind() + " container, e.g. with your compose environment on the Docker page "
                            + "(kitout compose up <environment>). kitout does not define these containers")).asAdvisory());
        });
        return results;
    }
}
