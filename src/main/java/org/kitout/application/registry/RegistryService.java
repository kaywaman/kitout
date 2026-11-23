package org.kitout.application.registry;

import org.kitout.application.config.ConfigRepository;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.config.KitoutConfig.ServiceSettings;
import org.kitout.application.port.CommandRunner.Result;
import org.kitout.application.port.ContainerRegistry;
import org.kitout.application.port.ContainerRegistry.RegistryException;
import org.kitout.application.run.DockerCli;
import org.kitout.application.run.ServiceSupervisor;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.domain.identity.Redactor;
import org.kitout.domain.project.Project;
import org.kitout.domain.run.ImageRef;
import org.kitout.domain.run.ImageTags;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Locale;

/**
 * The container registry: the developer's login (kept by Docker, separate from the GitLab token),
 * and the tags a service's image repository offers, for the tag picker.
 */
@ApplicationScoped
public class RegistryService {

    /**
     * @param username who Docker is logged in as; null when not logged in or not readable
     * @param detail   where the login comes from, or why there is none (for the Settings page)
     */
    public record Status(String host, boolean loggedIn, String username, String detail) {
    }

    /** @param current the tag chosen for the service; null if none yet */
    public record TagList(String serviceId, String repository, List<String> tags, String current) {
    }

    private final ConfigRepository config;
    private final RegistryLogins logins;
    private final ContainerRegistry registry;
    private final DockerCli docker;
    private final WorkspaceService workspace;
    private final ServiceSupervisor supervisor;

    public RegistryService(ConfigRepository config, RegistryLogins logins, ContainerRegistry registry, DockerCli docker,
                           WorkspaceService workspace, ServiceSupervisor supervisor) {
        this.config = config;
        this.logins = logins;
        this.registry = registry;
        this.docker = docker;
        this.workspace = workspace;
        this.supervisor = supervisor;
    }

    /** The login for the registry in Settings. */
    public Status status() {
        String registry = config.load().gitlab().registryHost();
        if (registry.isEmpty()) {
            return new Status("", false, null, "No container registry set");
        }
        RegistryLogins.Login login = logins.check(registry);
        return new Status(login.host(), login.loggedIn(), login.username(), login.detail());
    }

    /**
     * {@code docker login} with the given user and password or token. Docker keeps it; kitout does not.
     * The secret is passed on stdin and never logged.
     */
    public Status login(String username, String secret) {
        String host = requireHost();
        if (username == null || username.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Enter the registry user and its password or token");
        }
        Result r = docker.login(host, username.strip(), secret);
        if (!r.succeeded()) {
            throw new IllegalStateException(explainLogin(host, r));
        }
        return status();
    }

    public Status logout() {
        Result r = docker.logout(requireHost());
        if (!r.succeeded()) {
            throw new IllegalStateException("docker logout failed: " + Redactor.redact(r.firstLine()));
        }
        return status();
    }

    /** The tags of a service's image repository, newest-looking first, without {@code latest}. */
    public TagList tags(String serviceId) {
        Project project = workspace.require(serviceId);
        KitoutConfig cfg = config.load();
        ServiceSettings settings = cfg.serviceSettings(serviceId);
        String repository = supervisor.imageRepository(project, settings, cfg);
        ImageRef ref = ImageRef.parse(repository);
        try {
            return new TagList(serviceId, repository, ImageTags.forPicker(registry.tags(ref.registry(), ref.path())), settings.imageTag());
        } catch (RegistryException e) {
            if (e.kind() == RegistryException.Kind.UNAUTHORIZED) {
                RegistryLogins.Login login = logins.check(ref.registry());
                if (login.loggedIn() && !login.readable()) {
                    throw new IllegalStateException(login.detail());
                }
            }
            throw new IllegalStateException(e.getMessage());
        }
    }

    /** Sets the tag a service runs in IMAGE mode. Takes effect at its next start. */
    public void chooseTag(String serviceId, String tag) {
        workspace.require(serviceId);
        if (!ImageRef.isValidTag(tag)) {
            throw new IllegalArgumentException("'" + tag + "' is not a valid image tag");
        }
        if (tag.equalsIgnoreCase("latest")) {
            throw new IllegalArgumentException("kitout does not run 'latest': choose the tag that fits your task");
        }
        config.update(c -> c.withService(serviceId, c.serviceSettings(serviceId).withImageTag(tag)));
    }

    static String explainLogin(String host, Result r) {
        String out = r.output().toLowerCase(Locale.ROOT);
        if (r.notFound()) {
            return "The docker command was not found; install Docker first";
        }
        if (out.contains("unauthorized") || out.contains("denied") || out.contains("incorrect username or password")) {
            return host + " refused the user or password. Use the registry login your team gave you (it may differ from your git token).";
        }
        if (out.contains("no such host") || out.contains("timeout") || out.contains("connection refused")) {
            return host + " is not reachable; check the registry address in Settings and your network or VPN";
        }
        return "docker login failed: " + Redactor.redact(r.firstLine());
    }

    /** The host part of the registry setting: Docker logs in per host, whatever path prefix the images have. */
    private String requireHost() {
        String registry = config.load().gitlab().registryHost();
        if (registry.isEmpty()) {
            throw new IllegalStateException("Set the container registry in Settings first");
        }
        return RegistryLogins.hostOf(registry);
    }
}
