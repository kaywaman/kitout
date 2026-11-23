package org.kitout.infrastructure.secrets;

import org.kitout.application.KitoutPaths;
import org.kitout.application.port.CommandRunner;
import org.kitout.application.port.CommandRunner.Command;
import org.kitout.application.port.CommandRunner.Result;
import org.kitout.application.port.SecretStore;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

/**
 * kitout's fallback token store: the system keyring via {@code secret-tool} (libsecret), or, where
 * no keyring runs, a file readable only by the user. The token goes to {@code secret-tool} on
 * stdin, never as an argument, so it never shows up in the process list.
 */
@ApplicationScoped
public class KeyringSecretStore implements SecretStore {

    private static final String SERVICE = "kitout";

    static final String ENV_PREFIX = "kitout-env/";
    private final CommandRunner runner;
    private final KitoutPaths paths;

    public KeyringSecretStore(CommandRunner runner, KitoutPaths paths) {
        this.runner = runner;
        this.paths = paths;
    }

    @Override
    public Optional<String> lookup(String host) {
        return read(SERVICE, host);
    }

    private Optional<String> read(String service, String host) {
        Result r = runner.run(Command.of("secret-tool", "lookup", "service", service, "host", host));
        if (r.succeeded() && !r.output().isBlank()) {
            return Optional.of(r.output().strip());
        }
        Path file = fallbackFile(host);
        try {
            return Files.exists(file) ? Optional.of(Files.readString(file).strip()).filter(s -> !s.isEmpty()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void store(String host, String token) {
        Result r = runner.run(Command.of("secret-tool", "store", "--label=kitout " + (host.startsWith(ENV_PREFIX) ? "secret " : "git token ") + host,
                "service", SERVICE, "host", host).withStdin(token));
        if (r.succeeded()) {
            deleteFile(host);
            return;
        }
        writeFile(host, token);
    }

    @Override
    public void delete(String host) {
        runner.run(Command.of("secret-tool", "clear", "service", SERVICE, "host", host));
        deleteFile(host);
    }

    @Override
    public String location() {
        return runner.run(Command.of("secret-tool", "--version")).notFound()
                ? "a file readable only by you (" + paths.fallbackTokenDir() + ")"
                : "the system keyring";
    }

    private Path fallbackFile(String host) {
        return paths.fallbackTokenDir().resolve(host.replaceAll("[^a-zA-Z0-9.-]", "_") + ".token");
    }

    private void writeFile(String host, String token) {
        Path file = fallbackFile(host);
        try {
            Files.createDirectories(file.getParent());
            Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("rwx------"));
            Files.deleteIfExists(file);
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(file, token);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store the token: " + e.getMessage(), e);
        }
    }

    private void deleteFile(String host) {
        try {
            Files.deleteIfExists(fallbackFile(host));
        } catch (IOException e) {
            // nothing stored there
        }
    }
}
