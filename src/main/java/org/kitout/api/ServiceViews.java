package org.kitout.api;

import org.kitout.api.dto.ServiceView;
import org.kitout.application.config.ConfigRepository;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.run.ServiceStatus;
import org.kitout.application.run.ServiceSupervisor;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.domain.project.Project;
import org.kitout.domain.run.RunMode;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds {@link ServiceView}s: project facts, live status, settings and the image in use. */
@ApplicationScoped
public class ServiceViews {

    private final WorkspaceService workspace;
    private final ServiceSupervisor supervisor;
    private final ConfigRepository config;

    public ServiceViews(WorkspaceService workspace, ServiceSupervisor supervisor, ConfigRepository config) {
        this.workspace = workspace;
        this.supervisor = supervisor;
        this.config = config;
    }

    public List<ServiceView> all() {
        KitoutConfig cfg = config.load();
        Map<String, ServiceStatus> statuses = supervisor.statuses().stream()
                .collect(Collectors.toMap(ServiceStatus::serviceId, Function.identity()));
        return workspace.projects().stream()
                .filter(p -> statuses.containsKey(p.id()))
                .map(p -> view(p, statuses.get(p.id()), cfg))
                .toList();
    }

    public ServiceView one(String serviceId) {
        return view(workspace.require(serviceId), supervisor.status(serviceId), config.load());
    }

    public ServiceView of(Project p) {
        return one(p.id());
    }

    private ServiceView view(Project p, ServiceStatus status, KitoutConfig cfg) {
        KitoutConfig.ServiceSettings settings = ServiceSupervisor.fit(p, cfg.serviceSettings(p.id()));   // the mode the service really uses
        String image = null;
        String repository = null;
        try {
            repository = supervisor.imageRepository(p, settings, cfg);
            if (settings.runMode() == RunMode.IMAGE && !ServiceSupervisor.needsTag(settings)) {
                image = supervisor.image(p, settings, cfg);
            }
        } catch (IllegalStateException e) {
            // no registry or no git origin: the UI shows the reason when the developer picks a tag
        }
        return ApiMapper.service(p, status, settings, image, repository);
    }
}
