package org.kitout.application.run;

import org.kitout.application.port.CommandRunner;
import org.kitout.domain.run.LaunchPlanner;
import org.kitout.application.port.CommandRunner.Command;
import org.kitout.application.port.CommandRunner.Result;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.List;

/** The docker command line, as kitout needs it. Changes nothing but kitout's own containers and, on request, the registry login. */
@ApplicationScoped
public class DockerCli {

    private final CommandRunner runner;

    public DockerCli(CommandRunner runner) {
        this.runner = runner;
    }

    public Result info() {
        return runner.run(Command.of("docker", "info", "--format", "{{.ServerVersion}}").withTimeout(Duration.ofSeconds(10)));
    }

    public Result pull(String image) {
        return runner.run(Command.of("docker", "pull", "--quiet", image).withTimeout(Duration.ofMinutes(10)));
    }

    public Result manifestInspect(String image) {
        return runner.run(Command.of("docker", "manifest", "inspect", image).withTimeout(Duration.ofSeconds(30)));
    }

    /** True if a container of this name with kitout's label exists. */
    public boolean containerExists(String name) {
        Result r = runner.run(Command.of("docker", "ps", "-a", "--filter", "name=^" + name + "$",
                "--filter", "label=" + LaunchPlanner.LABEL, "--format", "{{.Names}}"));
        return r.succeeded() && r.output().strip().equals(name);
    }

    public Result stop(String name, int graceSeconds) {
        return runner.run(Command.of("docker", "stop", "-t", String.valueOf(graceSeconds), name)
                .withTimeout(Duration.ofSeconds(graceSeconds + 15L)));
    }

    /**
     * {@code docker login}: Docker stores the login in its own credential store, where every
     * docker command and kitout's tag picker find it. The secret goes through stdin, never an argument.
     */
    public Result login(String registryHost, String username, String secret) {
        return runner.run(Command.of("docker", "login", registryHost, "--username", username, "--password-stdin")
                .withStdin(secret).withTimeout(Duration.ofSeconds(30)));
    }

    /**
     * Asks Docker itself whether it has a working login: {@code docker login <host>} without user
     * or password reuses the stored credentials ("Authenticating with existing credentials") and
     * fails at once when there are none, because stdin is not a terminal. Changes nothing.
     */
    public Result verifyLogin(String registryHost) {
        return runner.run(Command.of("docker", "login", registryHost).withStdin("").withTimeout(Duration.ofSeconds(20)));
    }

    public Result logout(String registryHost) {
        return runner.run(Command.of("docker", "logout", registryHost).withTimeout(Duration.ofSeconds(15)));
    }

    /** One local image: {@code repository} without tag, {@code tag} may be {@code <none>}. */
    public record LocalImage(String repository, String tag, String id, String size) {

        public String reference() {
            return repository + ":" + tag;
        }
    }

    /** The images on this machine; empty if docker cannot be asked. */
    public List<LocalImage> localImages() {
        Result r = runner.run(Command.of("docker", "images", "--format", "{{.Repository}}\t{{.Tag}}\t{{.ID}}\t{{.Size}}")
                .withTimeout(Duration.ofSeconds(30)));
        if (!r.succeeded()) {
            return List.of();
        }
        return r.output().lines().map(l -> l.split("\t", -1)).filter(f -> f.length >= 4)
                .map(f -> new LocalImage(f[0].strip(), f[1].strip(), f[2].strip(), f[3].strip())).toList();
    }

    /** The local images of one repository, newest information first as docker lists it; empty if docker cannot be asked. */
    public List<org.kitout.domain.run.ImageRetention.Version> imageVersions(String repository) {
        Result r = runner.run(Command.of("docker", "images", repository, "--no-trunc", "--format", "{{.ID}}\t{{.Tag}}\t{{.CreatedAt}}")
                .withTimeout(Duration.ofSeconds(30)));
        if (!r.succeeded()) {
            return List.of();
        }
        return r.output().lines().map(l -> l.split("\t", -1)).filter(f -> f.length >= 3)
                .map(f -> new org.kitout.domain.run.ImageRetention.Version(f[0].strip(), f[1].strip(), f[2].strip())).toList();
    }

    /** Image ids of all containers, running or stopped: those images cannot be removed. */
    public java.util.Set<String> imagesOfContainers() {
        Result r = runner.run(Command.of("docker", "ps", "-a", "--no-trunc", "--format", "{{.Image}}"));
        if (!r.succeeded()) {
            return java.util.Set.of();
        }
        java.util.Set<String> refs = new java.util.HashSet<>();
        r.output().lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(refs::add);
        if (refs.isEmpty()) {
            return refs;
        }
        // a container names its image by reference; resolve to ids so tags and ids compare
        List<String> args = new java.util.ArrayList<>(List.of("docker", "image", "inspect", "--format", "{{.Id}}"));
        args.addAll(refs);
        Result ids = runner.run(Command.of(args).withTimeout(Duration.ofSeconds(30)));
        ids.output().lines().map(String::strip).filter(l -> l.startsWith("sha256:")).forEach(refs::add);
        return refs;
    }

    /** Removes one image by reference. Fails (and changes nothing) if a container still uses it. */
    public Result removeImage(String reference) {
        return runner.run(Command.of("docker", "rmi", reference).withTimeout(Duration.ofMinutes(2)));
    }

    /** Names of all containers with kitout's label, running or not. */
    public List<String> kitoutContainers() {
        Result r = runner.run(Command.of("docker", "ps", "-a", "--filter", "label=" + LaunchPlanner.LABEL, "--format", "{{.Names}}"));
        if (!r.succeeded()) {
            return List.of();
        }
        return r.output().lines().map(String::strip).filter(n -> !n.isEmpty()).distinct().toList();
    }

    /** Removes a leftover kitout container (e.g. after a crash). Only containers with kitout's label exist here. */
    public Result removeLeftover(String name) {
        return runner.run(Command.of("docker", "rm", "-f", name));
    }
}
