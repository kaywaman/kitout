package org.kitout.application.doctor.checks;

import org.kitout.application.doctor.DoctorCheck;
import org.kitout.application.doctor.DoctorContext;
import org.kitout.domain.doctor.CheckResult;
import org.kitout.domain.project.Project;
import org.kitout.domain.routing.BffRoutingAnalyzer;
import org.kitout.domain.routing.BffRoutingAnalyzer.Finding;
import org.kitout.domain.routing.RoutingMember;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** The BFF's dev URLs point at the right group members. Advisory: based on files only. */
@ApplicationScoped
public class BffRoutingCheck implements DoctorCheck {

    @Override
    public String id() {
        return "bff-routing";
    }

    @Override
    public int order() {
        return 96;
    }

    @Override
    public boolean appliesTo(DoctorContext context) {
        return context.bffs().stream().anyMatch(Project::looksLikeBff);
    }

    /** One result per BFF of the group that routes to others. */
    @Override
    public List<CheckResult> run(DoctorContext context) {
        List<Project> bffs = context.bffs().stream().filter(Project::looksLikeBff).toList();
        return bffs.stream().map(bff -> check(context, bff, bffs.size() > 1 ? "BFF routing of " + bff.name() : "BFF routing")).toList();
    }

    private CheckResult check(DoctorContext context, Project bff, String title) {
        List<RoutingMember> services = context.members().stream()
                .filter(m -> !m.id().equals(bff.id()))
                .map(m -> new RoutingMember(m.id(), m.name(), m.devPort()))
                .toList();
        List<Finding> findings = BffRoutingAnalyzer.analyze(bff.restClientUrls(),
                new RoutingMember(bff.id(), bff.name(), bff.devPort()), services);
        List<String> problems = findings.stream().filter(Finding::isProblem).map(Finding::message).toList();
        List<String> all = findings.stream().map(Finding::message).toList();
        if (problems.isEmpty()) {
            return CheckResult.ok(id(), title, findings.size() + " routes match the group's ports")
                    .withItems(all).asAdvisory();
        }
        return CheckResult.warning(id(), title, problems.size() + " of " + findings.size()
                                + " routes look wrong",
                        "Fix the quarkus.rest-client.<name>.url values in the BFF's application.properties (%dev profile)")
                .withItems(problems).asAdvisory();
    }
}
