package org.kitout.application.doctor.checks;

import org.kitout.application.doctor.DoctorCheck;
import org.kitout.application.doctor.DoctorContext;
import org.kitout.domain.doctor.CheckResult;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Files;
import java.util.List;

/** Every repository has its Maven or Gradle wrapper, so builds use the project's tool version. */
@ApplicationScoped
public class WrapperCheck implements DoctorCheck {

    @Override
    public String id() {
        return "build-wrapper";
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public boolean appliesTo(DoctorContext context) {
        return !context.members().isEmpty();
    }

    @Override
    public List<CheckResult> run(DoctorContext context) {
        List<String> missing = context.members().stream()
                .filter(m -> m.buildTool().java())
                .filter(m -> !Files.isExecutable(m.path().resolve(m.buildTool().wrapper())))
                .map(m -> m.name() + " (" + m.buildTool().wrapper() + ")")
                .toList();
        if (missing.isEmpty()) {
            return List.of(CheckResult.ok(id(), "Build wrappers", "All " + context.members().size() + " repositories have one"));
        }
        return List.of(CheckResult.warning(id(), "Build wrappers", missing.size() + " repositories have no executable wrapper",
                        "Add the wrapper (mvn wrapper:wrapper or gradle wrapper) or make it executable (chmod +x)")
                .withItems(missing));
    }
}
