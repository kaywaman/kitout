package org.kitout.api;

import org.kitout.api.dto.ScanRequest;
import org.kitout.api.dto.ScanView;
import org.kitout.api.dto.ServiceSettingsView;
import org.kitout.api.dto.ServiceView;
import org.kitout.application.KitoutPaths;
import org.kitout.application.config.ConfigRepository;
import org.kitout.application.config.KitoutConfig.ServiceSettings;
import org.kitout.application.config.KitoutConfig.VolumeMount;
import org.kitout.application.run.ServiceOptions;
import org.kitout.application.run.LogService;
import org.kitout.application.run.ServiceSupervisor;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.application.workspace.WorkspaceService.ScanResult;
import org.kitout.domain.run.RunMode;
import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/** Services: scan, list, start/stop/restart, settings and the live log. */
@Path("/api")
public class ServicesResource {

    private final ServiceViews views;
    private final ServiceSupervisor supervisor;
    private final WorkspaceService workspace;
    private final ConfigRepository config;
    private final LogService logs;
    private final KitoutPaths paths;
    private final ServiceOptions options;

    public ServicesResource(ServiceViews views, ServiceSupervisor supervisor, WorkspaceService workspace,
                            ConfigRepository config, LogService logs, KitoutPaths paths, ServiceOptions options) {
        this.options = options;
        this.views = views;
        this.supervisor = supervisor;
        this.workspace = workspace;
        this.config = config;
        this.logs = logs;
        this.paths = paths;
    }

    @POST
    @Path("/scan")
    public ScanView scan(ScanRequest request) {
        ScanResult result = workspace.scan(request == null ? List.of() : request.roots());
        return new ScanView(result.found().stream().map(views::of).toList(), result.added(), result.removed(), result.outsideTeam());
    }

    @GET
    @Path("/services")
    public List<ServiceView> services() {
        return views.all();
    }

    @GET
    @Path("/services/{id}")
    public ServiceView service(@PathParam("id") String id) {
        return views.one(id);
    }

    @POST
    @Path("/services/{id}/start")
    public ServiceView start(@PathParam("id") String id) {
        supervisor.start(id);
        return views.one(id);
    }

    @POST
    @Path("/services/{id}/stop")
    public ServiceView stop(@PathParam("id") String id) {
        supervisor.stop(id);
        return views.one(id);
    }

    @POST
    @Path("/services/{id}/restart")
    public ServiceView restart(@PathParam("id") String id) {
        supervisor.restart(id);
        return views.one(id);
    }

    @PUT
    @Path("/services/{id}/settings")
    public ServiceView updateSettings(@PathParam("id") String id, ServiceSettingsView change) {
        org.kitout.domain.project.Project project = workspace.require(id);
        if (change.mode() != null) {
            RunMode wanted = RunMode.valueOf(change.mode().toUpperCase(Locale.ROOT));
            if (!project.type().runModes().contains(wanted)) {
                throw new IllegalArgumentException(project.name() + " is a " + project.type().label() + " project: it runs from "
                        + String.join(", ", project.type().runModes().stream().map(m -> m.name().toLowerCase(Locale.ROOT)).toList())
                        + ", not " + wanted.name().toLowerCase(Locale.ROOT));
            }
        }
        if (change.runtimeProfile() != null && !change.runtimeProfile().isBlank()
                && !change.runtimeProfile().strip().matches("[A-Za-z0-9_.-]+(,[A-Za-z0-9_.-]+)*")) {
            throw new IllegalArgumentException("A profile is letters, digits, '.', '-' and '_' (Spring Boot: several joined by commas), not "
                    + change.runtimeProfile());
        }
        if (change.port() != null && (change.port() < 0 || change.port() > 65535)) {
            throw new IllegalArgumentException("A port is between 1 and 65535 (0 for the detected one)");
        }
        if (change.script() != null && !change.script().isBlank() && project.type() == org.kitout.domain.project.ProjectType.NODE
                && !project.scripts().contains(change.script().strip())
                && !org.kitout.domain.config.NodePackage.scripts(readPackageJson(project)).containsKey(change.script().strip())) {
            throw new IllegalArgumentException(project.name() + "'s package.json has no script " + change.script().strip());
        }
        // checked and saved first: a volume or option that cannot work changes nothing
        if (change.volumes() != null || change.dockerArgs() != null || change.healthCheck() != null) {
            options.update(id, change.volumes() == null ? null
                            : change.volumes().stream().map(v -> new VolumeMount(v.hostPath(), v.containerPath(), v.readOnly())).toList(),
                    change.dockerArgs(), change.healthCheck());
        }
        config.update(c -> {
            ServiceSettings s = c.serviceSettings(id);
            RunMode mode = change.mode() == null ? s.runMode() : RunMode.valueOf(change.mode().toUpperCase(Locale.ROOT));
            String image = change.image() == null ? s.image() : (change.image().isBlank() ? null : change.image().strip());
            String profile = change.runtimeProfile() == null ? s.runtimeProfile() : change.runtimeProfile().strip();
            int timeout = change.startTimeoutSeconds() == null ? s.startTimeoutSeconds() : change.startTimeoutSeconds();
            return c.withService(id, new ServiceSettings(s.path(), mode, image, profile, timeout, s.jvmArgs(), s.imageTag(), s.environment(),
                    s.volumes(), s.dockerArgs(), s.healthCheck(),
                    change.port() == null ? s.port() : (change.port() == 0 ? null : change.port()),
                    change.script() == null ? s.script() : change.script()));
        });
        return views.one(id);
    }

    /** Server-sent events, one log line per event: the last {@code lines} lines, then new ones as they come. */
    @GET
    @Path("/services/{id}/logs")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    public Multi<String> logs(@PathParam("id") String id, @QueryParam("lines") @DefaultValue("300") int lines) {
        if (workspace.find(id).isEmpty()) {
            throw new NoSuchElementException("Unknown service '" + id + "'");
        }
        return logs.follow(paths.serviceLog(id), Math.max(0, Math.min(lines, 5000)));
    }

    private static String readPackageJson(org.kitout.domain.project.Project project) {
        try {
            return java.nio.file.Files.readString(project.path().resolve("package.json"));
        } catch (java.io.IOException e) {
            return "";
        }
    }
}
