package org.kitout.application.doctor.checks;

import org.kitout.application.doctor.DoctorCheck;
import org.kitout.application.doctor.DoctorContext;
import org.kitout.application.port.CommandRunner;
import org.kitout.application.port.CommandRunner.Command;
import org.kitout.application.port.CommandRunner.Result;
import org.kitout.domain.doctor.CheckResult;
import org.kitout.domain.project.Project;
import org.kitout.domain.run.RunMode;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The installed JDK is at least what the projects compile for. */
@ApplicationScoped
public class JdkCheck implements DoctorCheck {

    private static final Pattern VERSION = Pattern.compile("version \"(\\d+)(?:\\.(\\d+))?");
    private final CommandRunner runner;

    public JdkCheck(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public String id() {
        return "jdk";
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public List<CheckResult> run(DoctorContext context) {
        Result r = runner.run(Command.of("java", "-version"));
        OptionalInt installed = r.succeeded() ? parseMajor(r.output()) : OptionalInt.empty();
        int required = context.members().stream().map(Project::javaRelease)
                .flatMap(Optional::stream).mapToInt(Integer::intValue).max().orElse(0);
        boolean jvmRuns = context.members().stream().anyMatch(m -> context.runMode(m) == RunMode.JVM);
        if (installed.isEmpty()) {
            CheckResult missing = jvmRuns
                    ? CheckResult.error(id(), "JDK", "No java command found", "Install a JDK" + (required > 0 ? " " + required : ""))
                    : CheckResult.warning(id(), "JDK", "No java command found; local builds will fail",
                    "Install a JDK" + (required > 0 ? " " + required : ""));
            return List.of(missing);
        }
        if (required > installed.getAsInt()) {
            String detail = "JDK " + installed.getAsInt() + " installed, but projects need " + required;
            String fix = "Install JDK " + required + " and make it the default java";
            return List.of(jvmRuns ? CheckResult.error(id(), "JDK", detail, fix) : CheckResult.warning(id(), "JDK", detail, fix));
        }
        return List.of(CheckResult.ok(id(), "JDK", "JDK " + installed.getAsInt()
                + (required > 0 ? " (projects need " + required + ")" : "")));
    }

    static OptionalInt parseMajor(String versionOutput) {
        Matcher m = VERSION.matcher(versionOutput);
        if (!m.find()) {
            return OptionalInt.empty();
        }
        int first = Integer.parseInt(m.group(1));
        // Java 8 and older report "1.8.0_x"
        return OptionalInt.of(first == 1 && m.group(2) != null ? Integer.parseInt(m.group(2)) : first);
    }
}
