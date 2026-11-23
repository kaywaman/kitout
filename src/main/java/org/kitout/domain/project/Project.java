package org.kitout.domain.project;

import org.kitout.domain.config.Endpoint;
import org.kitout.domain.config.HealthEndpoint;
import org.kitout.domain.identity.RepoIdentity;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What kitout knows about one service: a project on disk (Quarkus, Spring Boot, plain Java, Node),
 * or an image the team file names. Detected, never typed by hand (except an image-only service's
 * port, which the team file gives, and a port override the developer sets).
 *
 * @param id                  stable, URL-safe service id, e.g. {@code acme.shop.orders}
 * @param name                display name, the project name from GitLab or the folder name
 * @param path                the checkout; for an image-only service kitout's state folder (nothing is written there)
 * @param identity            from the git origin; empty for repos without a GitLab remote and for image-only services
 * @param devPort             the port it answers on: from its config, or 8080; 0 when it has none (a plain Java app without a port)
 * @param devPortAdvisory     true if the port depends on an environment variable, or is a guess (Node)
 * @param javaRelease         {@code maven.compiler.release} or Gradle toolchain version, if found
 * @param infrastructure      databases and brokers from the dev profile
 * @param restClientUrls      rest client name to dev URL; non-empty mainly for the BFF (Quarkus)
 * @param health              where the readiness check answers; an empty path: the project has none, so the port is watched
 * @param type                Quarkus, Spring Boot, Java, Node or image only
 * @param scripts             a Node project's npm scripts that can start it ({@code dev}, {@code start}, ...); empty otherwise
 * @param image               an image-only service's repository in the registry; empty for projects (derived from the origin)
 */
public record Project(
        String id,
        String name,
        Path path,
        Optional<RepoIdentity> identity,
        BuildTool buildTool,
        int devPort,
        boolean devPortAdvisory,
        Optional<Integer> javaRelease,
        List<Endpoint> infrastructure,
        Map<String, String> restClientUrls,
        HealthEndpoint health,
        Optional<String> version,
        ProjectType type,
        List<String> scripts,
        Optional<String> image) {

    public Project {
        infrastructure = List.copyOf(infrastructure);
        restClientUrls = Map.copyOf(restClientUrls);
        version = version == null ? Optional.empty() : version;
        type = type == null ? ProjectType.QUARKUS : type;
        scripts = scripts == null ? List.of() : List.copyOf(scripts);
        image = image == null ? Optional.empty() : image;
    }

    /** A Quarkus project (as before there were other types). */
    public Project(String id, String name, Path path, Optional<RepoIdentity> identity, BuildTool buildTool, int devPort,
                   boolean devPortAdvisory, Optional<Integer> javaRelease, List<Endpoint> infrastructure,
                   Map<String, String> restClientUrls, HealthEndpoint health, Optional<String> version) {
        this(id, name, path, identity, buildTool, devPort, devPortAdvisory, javaRelease, infrastructure, restClientUrls, health,
                version, ProjectType.QUARKUS, List.of(), Optional.empty());
    }

    public Project(String id, String name, Path path, Optional<RepoIdentity> identity, BuildTool buildTool, int devPort,
                   boolean devPortAdvisory, Optional<Integer> javaRelease, List<Endpoint> infrastructure,
                   Map<String, String> restClientUrls, HealthEndpoint health) {
        this(id, name, path, identity, buildTool, devPort, devPortAdvisory, javaRelease, infrastructure, restClientUrls, health,
                Optional.empty());
    }

    /** A project that routes to other services; the group's entry point, started last. */
    public boolean looksLikeBff() {
        return !restClientUrls.isEmpty();
    }

    /** It has a readiness endpoint kitout can ask (Quarkus, Spring Boot, an image the team gave a health path). */
    public boolean hasHealthEndpoint() {
        return health.path() != null && !health.path().isBlank();
    }

    /** It has a checkout on this machine: everything but an image-only service. */
    public boolean hasCheckout() {
        return type != ProjectType.IMAGE;
    }

    /** The same project on another port (the developer's override); a health check on the same port moves with it. */
    public Project withPort(int port) {
        HealthEndpoint h = health.port() == devPort ? new HealthEndpoint(port, health.path(), health.advisory()) : health;
        return new Project(id, name, path, identity, buildTool, port, false, javaRelease, infrastructure, restClientUrls, h,
                version, type, scripts, image);
    }

    public static String idFor(Optional<RepoIdentity> identity, Path path) {
        String raw = identity.map(RepoIdentity::fullPath).orElse(path.getFileName().toString());
        return raw.toLowerCase().replaceAll("[^a-z0-9._-]+", ".").replaceAll("^\\.+|\\.+$", "");
    }
}
