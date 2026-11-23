package org.kitout.cli.commands;

import org.kitout.application.doctor.DoctorService;
import org.kitout.application.doctor.DoctorService.Report;
import org.kitout.cli.Console;
import org.kitout.domain.doctor.Severity;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.Optional;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "doctor", description = "Check the environment and say exactly what is missing.")
public class DoctorCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"-g", "--group"}, description = "Also check this group's services.")
    String group;

    @Inject
    DoctorService doctor;

    @Override
    public Integer call() {
        Report report = doctor.run(Optional.ofNullable(group));
        report.results().forEach(Console::check);
        Console.out("");
        Console.out(report.count(Severity.ERROR) + " errors, " + report.count(Severity.WARNING) + " warnings.");
        return report.hasErrors() ? Console.FAILED : Console.OK;
    }
}
