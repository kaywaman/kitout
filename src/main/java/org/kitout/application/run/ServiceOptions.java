package org.kitout.application.run;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.ConfigRepository;
import org.kitout.application.config.KitoutConfig.ServiceSettings;
import org.kitout.application.config.KitoutConfig.VolumeMount;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.domain.run.DockerOptions;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

/**
 * A service's container options (host folders, extra {@code docker run} options) and its health
 * check switch. Checked when saved, so a mistake shows where it was typed, not at the next start.
 * Whether a host folder exists is checked at start: it may be created later.
 */
@ApplicationScoped
public class ServiceOptions {

    private final ConfigRepository config;
    private final WorkspaceService workspace;
    private final KitoutPaths paths;

    public ServiceOptions(ConfigRepository config, WorkspaceService workspace, KitoutPaths paths) {
        this.config = config;
        this.workspace = workspace;
        this.paths = paths;
    }

    /**
     * Saves what is given; a null argument keeps the current value.
     *
     * @throws IllegalArgumentException with the sentence to show, for a volume or option that cannot work
     */
    public ServiceSettings update(String serviceId, List<VolumeMount> volumes, String dockerArgs, Boolean healthCheck) {
        return update(serviceId, volumes, dockerArgs, healthCheck, null, null, null);
    }

    /**
     * @param port    the port instead of the detected one; 0 goes back to the detected one; null keeps it
     * @param script  a Node project's npm script; empty goes back to dev/start; null keeps it
     * @param profile the run profile; null keeps it
     */
    public ServiceSettings update(String serviceId, List<VolumeMount> volumes, String dockerArgs, Boolean healthCheck,
                                  Integer port, String script, String profile) {
        org.kitout.domain.project.Project project = workspace.require(serviceId);
        if (port != null && (port < 0 || port > 65535)) {
            throw new IllegalArgumentException("A port is between 1 and 65535 (0 for the detected one)");
        }
        if (script != null && !script.isBlank() && !project.scripts().contains(script.strip())
                && project.type() != org.kitout.domain.project.ProjectType.NODE) {
            throw new IllegalArgumentException(project.name() + " is not a Node project: it has no npm scripts");
        }
        if (profile != null && !profile.strip().matches("[A-Za-z0-9_.-]+(,[A-Za-z0-9_.-]+)*")) {
            throw new IllegalArgumentException("A profile is letters, digits, '.', '-' and '_' (Spring Boot: several joined by commas)");
        }
        List<VolumeMount> checked = volumes == null ? null : check(volumes);
        if (dockerArgs != null) {
            DockerOptions.parse(dockerArgs);
        }
        return config.update(c -> {
            ServiceSettings s = c.serviceSettings(serviceId);
            ServiceSettings changed = s.withDocker(checked == null ? s.volumes() : checked, dockerArgs == null ? s.dockerArgs() : dockerArgs)
                    .withHealthCheck(healthCheck == null ? s.healthCheck() : healthCheck)
                    .withPortAndScript(port == null ? s.port() : (port == 0 ? null : port), script == null ? s.script() : script)
                    .withRuntimeProfile(profile == null ? s.runtimeProfile() : profile.strip());
            return c.withService(serviceId, changed);
        }).serviceSettings(serviceId);
    }

    List<VolumeMount> check(List<VolumeMount> volumes) {
        List<VolumeMount> result = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        for (VolumeMount v : volumes) {
            if (v == null || (v.hostPath().isEmpty() && v.containerPath().isEmpty())) {
                continue;   // an empty row in the form
            }
            if (v.hostPath().isEmpty() || v.containerPath().isEmpty()) {
                throw new IllegalArgumentException("A volume needs both paths: the folder on this machine and where it appears in the container");
            }
            DockerOptions.mount(paths.expand(v.hostPath()).toAbsolutePath().normalize().toString(), v.containerPath(), v.readOnly());
            if (targets.contains(v.containerPath())) {
                throw new IllegalArgumentException(v.containerPath() + " is mounted twice in the container");
            }
            targets.add(v.containerPath());
            result.add(v);
        }
        return result;
    }
}
