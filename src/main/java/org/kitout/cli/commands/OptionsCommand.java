package org.kitout.cli.commands;

import org.kitout.application.config.ConfigRepository;
import org.kitout.application.config.KitoutConfig.ServiceSettings;
import org.kitout.application.config.KitoutConfig.VolumeMount;
import org.kitout.application.run.ServiceOptions;
import org.kitout.cli.Console;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** {@code kitout options <service>}: host folders, extra docker run options and the health check of one service. */
@CommandLine.Command(name = "options",
        description = {"Container options and the health check of a service. Without options: show them.",
                "Volumes and docker options apply when it runs as a registry image, from its next start."})
public class OptionsCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", paramLabel = "SERVICE")
    String service;

    @CommandLine.Option(names = {"-v", "--volume"}, paramLabel = "HOST:CONTAINER[:ro]",
            description = "A host folder mounted into the container, e.g. ~/certs:/deployments/certs:ro. Repeat for more; replaces the list.")
    List<String> volumes;

    @CommandLine.Option(names = "--no-volumes", description = "Remove all volumes.")
    boolean noVolumes;

    @CommandLine.Option(names = "--docker-args", paramLabel = "TEXT",
            description = "Extra docker run options, e.g. \"--memory 1g --add-host db.local:10.0.0.5\". An empty text removes them.")
    String dockerArgs;

    @CommandLine.Option(names = "--health-check", paramLabel = "on|off",
            description = "off: the service counts as started when its port answers (e.g. a BFF whose health endpoint is blocked).")
    String healthCheck;

    @CommandLine.Option(names = "--port", paramLabel = "PORT",
            description = "The port it answers on, instead of the detected one (plain Java apps, Node dev servers). 0: the detected one.")
    Integer port;

    @CommandLine.Option(names = "--script", paramLabel = "NAME", description = "A Node project's npm script to start it, e.g. dev. Empty: dev, else start.")
    String script;

    @CommandLine.Option(names = "--profile", paramLabel = "PROFILE",
            description = "The profile it runs with: Quarkus (quarkus.profile) and Spring Boot (spring.profiles.active), e.g. qa.")
    String profile;

    @Inject
    ServiceOptions options;

    @Inject
    ConfigRepository config;

    @Override
    public Integer call() {
        boolean change = volumes != null || noVolumes || dockerArgs != null || healthCheck != null || port != null || script != null || profile != null;
        ServiceSettings s = change
                ? options.update(service, noVolumes ? List.of() : volumes == null ? null : volumes.stream().map(OptionsCommand::volume).toList(),
                        dockerArgs, healthCheck == null ? null : onOff(healthCheck), port, script, profile)
                : config.load().serviceSettings(service);
        if (change) {
            Console.out(Console.ok("✓") + " Saved; applies from the next start of " + service + ".");
        }
        Console.out("Health check:   " + (s.checksHealth() ? "on (readiness check)" : "off (started when the port answers)"));
        Console.out("Volumes:        " + (s.volumes().isEmpty() ? "none" : ""));
        s.volumes().forEach(v -> Console.out("  " + v.hostPath() + " -> " + v.containerPath() + (v.readOnly() ? " (read-only)" : "")));
        Console.out("Docker options: " + (s.dockerArgs() == null ? "none" : s.dockerArgs()));
        Console.out("Profile:        " + s.runtimeProfile());
        Console.out("Port:           " + (s.port() == null ? "detected" : s.port()));
        if (s.script() != null) {
            Console.out("npm script:     " + s.script());
        }
        return Console.OK;
    }

    /** {@code host:container} or {@code host:container:ro}. */
    static VolumeMount volume(String text) {
        List<String> parts = new ArrayList<>(List.of(text.split(":", -1)));
        boolean readOnly = parts.size() == 3 && (parts.get(2).equals("ro") || parts.get(2).equals("readonly"));
        if (parts.size() == 3 && !readOnly && !parts.get(2).equals("rw")) {
            throw new IllegalArgumentException("Write a volume as HOST:CONTAINER or HOST:CONTAINER:ro, not " + text);
        }
        if (parts.size() < 2 || parts.size() > 3) {
            throw new IllegalArgumentException("Write a volume as HOST:CONTAINER or HOST:CONTAINER:ro, not " + text);
        }
        return new VolumeMount(parts.get(0), parts.get(1), readOnly);
    }

    static boolean onOff(String value) {
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "on", "true", "yes" -> true;
            case "off", "false", "no" -> false;
            default -> throw new IllegalArgumentException("--health-check is on or off, not " + value);
        };
    }
}
