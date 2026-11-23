package org.kitout.application.registry;

import org.kitout.application.port.CommandRunner.Result;
import org.kitout.application.port.RegistryCredentials;
import org.kitout.application.run.DockerCli;
import org.kitout.domain.identity.Redactor;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Locale;
import java.util.Optional;

/**
 * Whether the developer is logged in to a registry. kitout first reads Docker's credential store
 * (needed anyway for the tag list); when that finds nothing, it asks Docker itself, so a login
 * kitout cannot read never blocks a start or shows as "not logged in".
 */
@ApplicationScoped
public class RegistryLogins {

    /**
     * @param username null if unknown
     * @param readable kitout can read the login itself (the tag picker needs that)
     * @param detail   one sentence: where the login comes from, or why there is none
     */
    public record Login(String host, boolean loggedIn, String username, boolean readable, String detail) {
    }

    private final RegistryCredentials credentials;
    private final DockerCli docker;

    public RegistryLogins(RegistryCredentials credentials, DockerCli docker) {
        this.credentials = credentials;
        this.docker = docker;
    }

    public Login check(String registry) {
        String host = hostOf(registry);
        Optional<RegistryCredentials.Credential> stored = credentials.lookup(host);
        if (stored.isPresent()) {
            return new Login(host, true, stored.get().username(), true, "Docker keeps this login");
        }
        Result r = docker.verifyLogin(host);
        if (r.succeeded()) {
            return new Login(host, true, null, false, "Docker has a working login, but kitout cannot read it, so the tag list "
                    + "cannot use it. " + credentials.diagnose(host));
        }
        return new Login(host, false, null, false, explain(host, r));
    }

    /** {@code https://images.example.com/team} to {@code images.example.com}: Docker logs in per host. */
    public static String hostOf(String registry) {
        String s = registry.strip().replaceFirst("^[a-zA-Z]+://", "");
        int slash = s.indexOf('/');
        return (slash < 0 ? s : s.substring(0, slash)).toLowerCase(Locale.ROOT);
    }

    static String explain(String host, Result r) {
        String out = r.output().toLowerCase(Locale.ROOT);
        if (r.notFound()) {
            return "The docker command was not found";
        }
        if (out.contains("non tty") || out.contains("username") || out.contains("cannot perform an interactive login")) {
            return "No stored login for " + host;
        }
        if (out.contains("unauthorized") || out.contains("denied")) {
            return "The stored login for " + host + " is refused (expired or changed); log in again";
        }
        return "Docker could not confirm a login for " + host + ": " + Redactor.redact(r.firstLine());
    }
}
