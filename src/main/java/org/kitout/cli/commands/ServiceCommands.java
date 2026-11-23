package org.kitout.cli.commands;

import org.kitout.application.run.ServiceStatus;
import org.kitout.application.run.ServiceSupervisor;
import org.kitout.cli.Console;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/** {@code kitout start|stop|restart SERVICE}: one service at a time. */
public final class ServiceCommands {

    private ServiceCommands() {
    }

    abstract static class Base implements Callable<Integer> {

        @CommandLine.Parameters(paramLabel = "SERVICE", description = "Service id, as shown by 'kitout status'.")
        String service;

        @Inject
        ServiceSupervisor supervisor;

        abstract ServiceStatus act();

        @Override
        public Integer call() {
            try {
                ServiceStatus s = act();
                Console.out(s.name() + ": " + s.state() + (s.detail().isEmpty() ? "" : " - " + s.detail())
                        + s.startupTime().map(d -> " (started in " + ServiceSupervisor.formatSeconds(d) + ")").orElse(""));
                return Console.OK;
            } catch (IllegalStateException e) {
                Console.err(e.getMessage());
                return Console.FAILED;
            }
        }
    }

    @CommandLine.Command(name = "start", description = "Start one service (returns while it starts; see 'kitout status').")
    public static class Start extends Base {
        @Override
        ServiceStatus act() {
            return supervisor.start(service);
        }
    }

    @CommandLine.Command(name = "stop", description = "Stop one service and its whole process tree.")
    public static class Stop extends Base {
        @Override
        ServiceStatus act() {
            return supervisor.stop(service);
        }
    }

    @CommandLine.Command(name = "restart", description = "Stop, then start one service.")
    public static class Restart extends Base {
        @Override
        ServiceStatus act() {
            return supervisor.restart(service);
        }
    }
}
