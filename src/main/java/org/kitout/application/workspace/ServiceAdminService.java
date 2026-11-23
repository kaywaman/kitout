package org.kitout.application.workspace;

import org.kitout.application.config.ConfigRepository;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.config.KitoutConfig.ServiceSettings;
import org.kitout.application.run.ServiceStatus;
import org.kitout.application.run.ServiceSupervisor;
import org.kitout.domain.group.Group;
import org.kitout.domain.project.Project;
import org.kitout.domain.run.RunMode;
import org.kitout.domain.run.ServiceState;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * Changes to the list of services as a whole: removing one, removing all, showing a removed one
 * again, and setting the run mode of many services at once.
 *
 * <p>Removing never touches files on disk. It forgets the service in kitout and hides its folder
 * from future scans, which is what a developer wants for a library or an archived project that
 * looks like a Quarkus service.
 */
@ApplicationScoped
public class ServiceAdminService {

    public record RemoveAllResult(int removed) {
    }

    /** @param skipped services whose type has no such mode (a Node project has no JVM build), one sentence each */
    public record RunModeResult(int changed, int running, List<String> skipped) {

        public RunModeResult(int changed, int running) {
            this(changed, running, List.of());
        }
    }

    private final ConfigRepository config;
    private final WorkspaceService workspace;
    private final ServiceSupervisor supervisor;
    private final ProjectReader reader;

    public ServiceAdminService(ConfigRepository config, WorkspaceService workspace, ServiceSupervisor supervisor,
                               ProjectReader reader) {
        this.config = config;
        this.workspace = workspace;
        this.supervisor = supervisor;
        this.reader = reader;
    }

    /**
     * Forgets the service, hides its folder and takes it out of saved groups.
     *
     * <p>Refused while kitout runs it (any state but STOPPED, so also a FAILED service whose
     * process may still be alive): removing should never leave an orphaned process behind that
     * kitout no longer shows. The developer stops it first, which is one click.
     */
    public void remove(String serviceId) {
        ServiceSettings settings = config.load().services().get(serviceId);
        if (settings == null) {
            throw new NoSuchElementException("Unknown service '" + serviceId + "'");
        }
        statusOf(serviceId).filter(ServiceAdminService::blocksRemoval).ifPresent(s -> {
            throw new IllegalStateException(s.name() + " is " + s.state().name().toLowerCase(Locale.ROOT)
                    + ". Stop it first, then remove it.");
        });
        config.update(c -> {
            Map<String, ServiceSettings> services = new LinkedHashMap<>(c.services());
            services.remove(serviceId);
            List<String> hidden = new ArrayList<>(c.hiddenProjects());
            if (settings.path() != null && !hidden.contains(settings.path())) {
                hidden.add(settings.path());
            }
            return c.withServices(services)
                    .withGroups(withoutMembers(c.groups(), Set.of(serviceId)))
                    .withHiddenProjects(hidden);
        });
    }

    /**
     * Forgets every service, saved group and workspace folder, so the developer can start over by
     * choosing a folder. GitLab, build and doctor settings stay. Refused while kitout runs any
     * service, for the same reason as {@link #remove}; the message names them.
     */
    public RemoveAllResult removeAll() {
        List<String> running = blockingRemoval();
        if (!running.isEmpty()) {
            throw new IllegalStateException("Stop the running services first: " + String.join(", ", running) + ".");
        }
        int removed = config.load().services().size();
        config.update(c -> c.withServices(Map.of()).withGroups(List.of()).withWorkspaceRoots(List.of())
                .withHiddenProjects(List.of()));
        return new RemoveAllResult(removed);
    }

    /** Names of the services that kitout runs right now, which keep {@link #removeAll} from going ahead. */
    public List<String> blockingRemoval() {
        return supervisor.statuses().stream().filter(ServiceAdminService::blocksRemoval).map(ServiceStatus::name).toList();
    }

    /**
     * A service blocks its removal while kitout has a process for it. A service in IDE mode is
     * started by the developer, not by kitout, so it never blocks.
     */
    static boolean blocksRemoval(ServiceStatus s) {
        return s.mode() != RunMode.IDE && s.state() != ServiceState.STOPPED;
    }

    /** Shows a removed project again: un-hides its folder and adds it, if it still is a Quarkus project. */
    public Optional<Project> restore(String projectPath) {
        config.update(c -> c.withHiddenProjects(c.hiddenProjects().stream().filter(p -> !p.equals(projectPath)).toList()));
        Optional<Project> project = reader.read(Path.of(projectPath));
        project.ifPresent(p -> config.update(c -> c.services().containsKey(p.id())
                ? c
                : c.withService(p.id(), ServiceSettings.defaults(p.path().toString()))));
        return project;
    }

    public List<String> hiddenProjects() {
        return config.load().hiddenProjects();
    }

    /**
     * Sets the run mode of the given services, or of all known services when the list is empty.
     * A running service keeps its current process; the new mode applies at its next start.
     */
    public RunModeResult setRunMode(RunMode mode, List<String> serviceIds) {
        List<String> requested = serviceIds == null || serviceIds.isEmpty()
                ? workspace.projects().stream().map(Project::id).toList()
                : serviceIds;
        List<String> skipped = new java.util.ArrayList<>();
        List<String> targets = new java.util.ArrayList<>();
        for (String id : requested) {
            Optional<Project> p = workspace.find(id);
            if (p.isPresent() && !p.get().type().runModes().contains(mode)) {
                skipped.add(p.get().name() + " (" + p.get().type().label() + ") has no " + mode.name().toLowerCase(java.util.Locale.ROOT) + " mode");
            } else {
                targets.add(id);
            }
        }
        Set<String> targetSet = Set.copyOf(targets);
        int running = (int) supervisor.statuses().stream()
                .filter(s -> targetSet.contains(s.serviceId()))
                .filter(s -> s.mode() != mode && s.mode() != RunMode.IDE && s.state().isActive())
                .count();
        config.update(c -> {
            KitoutConfig result = c;
            for (String id : targets) {
                result = result.withService(id, result.serviceSettings(id).withRunMode(mode));
            }
            return result;
        });
        return new RunModeResult(targets.size(), running, skipped);
    }

    private Optional<ServiceStatus> statusOf(String serviceId) {
        try {
            return Optional.of(supervisor.status(serviceId));
        } catch (NoSuchElementException e) {
            return Optional.empty();   // folder already gone: nothing kitout could still be running from it
        }
    }

    static List<Group> withoutMembers(List<Group> groups, Set<String> removed) {
        List<Group> result = new ArrayList<>();
        for (Group g : groups) {
            Group left = g.withoutMembers(removed);
            if (left.members().isEmpty()) {
                continue;
            }
            result.add(left);
        }
        return result;
    }
}
