package org.kitout.cli.commands;

import org.kitout.application.docker.ComposeJob;
import org.kitout.application.docker.ComposeService;
import org.kitout.application.docker.ComposeService.Containers;
import org.kitout.application.docker.ComposeService.Environment;
import org.kitout.application.run.LogService;
import org.kitout.cli.Console;
import org.kitout.domain.docker.Container;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** {@code kitout compose}: the environments and their containers; {@code up} and {@code down} per environment. */
@CommandLine.Command(name = "compose",
        description = {"Docker compose environments from the folders in Settings, Docker (docker-compose-<environment>.yml).",
                "Without a subcommand: list the environments and their containers."},
        subcommands = {ComposeCommand.Up.class, ComposeCommand.Down.class, ComposeCommand.Pick.class, ComposeCommand.Forget.class})
public class ComposeCommand implements Callable<Integer> {

    @Inject
    ComposeService compose;

    @Override
    public Integer call() {
        List<Environment> environments = compose.environments();
        if (environments.isEmpty()) {
            Console.out("No compose environments. Pick a file with 'kitout compose pick <file>', or add the folder with your "
                    + "docker-compose-<environment>.yml files in Settings, Docker (UI).");
            return Console.OK;
        }
        Containers containers = compose.containers(true);
        if (!containers.available()) {
            Console.err("Docker is not available: " + containers.problem());
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"ENVIRONMENT", "RUNNING", "FILE", ""});
        for (Environment e : environments) {
            List<Container> mine = containers.list().stream().filter(c -> c.createdFrom(e.file().toString())).toList();
            String file = e.file() + (e.overrides().isEmpty() ? "" : " + " + e.overrides().size() + " override(s)");
            String note = e.team() ? (e.missing().isEmpty() ? "team" : "team, missing: " + String.join(", ", e.missing())) : e.picked() ? "picked" : "";
            rows.add(new String[] {e.id(), mine.stream().filter(Container::running).count() + "/" + mine.size(), file, note});
        }
        Console.table(rows);
        return containers.available() ? Console.OK : Console.FAILED;
    }

    abstract static class Run implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", paramLabel = "ENVIRONMENT", description = "e.g. dev for docker-compose-dev.yml")
        String environment;

        @Inject
        ComposeService compose;

        @Inject
        LogService logs;

        abstract ComposeJob.Action action();

        @Override
        public Integer call() throws InterruptedException {
            ComposeJob job = compose.start(environment, action());
            while (job.status() == ComposeJob.Status.RUNNING) {
                Thread.sleep(500);
            }
            logs.tail(job.logFile(), 200).forEach(Console::out);
            return job.status() == ComposeJob.Status.SUCCEEDED ? Console.OK : Console.FAILED;
        }
    }

    @CommandLine.Command(name = "up", description = "docker compose -f docker-compose-<environment>.yml up -d")
    public static class Up extends Run {
        @Override
        ComposeJob.Action action() {
            return ComposeJob.Action.UP;
        }
    }

    @CommandLine.Command(name = "down", description = "docker compose -f docker-compose-<environment>.yml down (volumes stay)")
    public static class Down extends Run {
        @Override
        ComposeJob.Action action() {
            return ComposeJob.Action.DOWN;
        }
    }

    @CommandLine.Command(name = "pick", description = "Add any compose file (inside your home folder) as an environment, whatever its name.")
    public static class Pick implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", paramLabel = "FILE")
        String file;

        @Inject
        ComposeService compose;

        @Override
        public Integer call() {
            Environment e = compose.pick(java.nio.file.Path.of(file).toAbsolutePath().toString());
            Console.out("Added " + e.file() + " as environment '" + e.id() + "'. Start it with: kitout compose up " + e.id());
            return Console.OK;
        }
    }

    @CommandLine.Command(name = "forget", description = "Remove a picked compose file from kitout; the file stays on disk.")
    public static class Forget implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", paramLabel = "FILE")
        String file;

        @Inject
        ComposeService compose;

        @Override
        public Integer call() {
            java.nio.file.Path given = java.nio.file.Path.of(file).toAbsolutePath().normalize();
            String path;
            try {
                path = given.toRealPath().toString();   // picked files are stored with links resolved
            } catch (java.io.IOException e) {
                path = given.toString();                // already deleted: match the stored path as typed
            }
            String real = path;
            String stored = compose.pickedFiles().keySet().stream()
                    .filter(p -> p.equals(real) || p.equals(file)).findFirst().orElse(real);
            compose.forget(stored);
            Console.out("Forgot " + stored + ". The file is unchanged.");
            return Console.OK;
        }
    }
}
