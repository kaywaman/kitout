package org.kitout.cli.commands;

import org.kitout.application.build.BuildJob;
import org.kitout.application.build.BuildService;
import org.kitout.cli.Console;
import org.kitout.domain.build.BuildMode;
import org.kitout.domain.build.BuildRequest;
import org.kitout.domain.build.BuildStatus;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "build", description = "Build a service locally. Native builds wait for each other.")
public class BuildCommand implements Callable<Integer> {

    @CommandLine.Parameters(paramLabel = "SERVICE")
    String service;

    @CommandLine.Option(names = {"-p", "--profile"}, defaultValue = "dev", description = "Quarkus build profile (default ${DEFAULT-VALUE}).")
    String profile;

    @CommandLine.Option(names = "--native", description = "Build a native executable instead of a JVM build.")
    boolean nativeBuild;

    @CommandLine.Option(names = "--skip-tests", description = "Do not run the tests.")
    boolean skipTests;

    @Inject
    BuildService builds;

    @Inject
    org.kitout.application.run.ServiceSupervisor supervisor;

    @Override
    public Integer call() {
        BuildRequest request = new BuildRequest(service, profile, nativeBuild ? BuildMode.NATIVE : BuildMode.JVM, skipTests);
        if (request.isProdProfile()) {
            Console.err("Warning: building with the prod profile locally.");
        }
        Console.out("Building " + service + " (" + request.mode() + ", profile " + profile + (skipTests ? ", skipping tests" : "") + ")...");
        var conflict = supervisor.cleanBuildConflict(request.serviceId());
        if (conflict.isPresent()) {
            Console.err(conflict.get());
            return Console.FAILED;
        }
        BuildJob job = builds.runAndWait(request);
        Console.out(job.status() + " - log: " + job.logFile());
        job.explanation().ifPresent(e -> {
            Console.out("Cause: " + e.cause());
            Console.out("Fix:   " + e.fix());
        });
        return job.status() == BuildStatus.SUCCEEDED ? Console.OK : Console.FAILED;
    }
}
