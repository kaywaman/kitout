package org.kitout.api.security;

import org.kitout.application.KitoutPaths;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The key the browser must send with every API call ({@code X-Kitout-Token}). It is handed over once
 * in the URL fragment (which browsers never send to a server) and then remembered by that browser.
 *
 * <p>The key is kept in {@code ~/.local/state/kitout/session-token}, readable only by the developer,
 * and reused across restarts: a browser that opened kitout once opens it again with the plain address.
 * Delete the file (or reset kitout's settings) to make every browser hand over a new link.
 *
 * <p>Why a key at all, on a local machine: the Host and Origin checks stop other websites, but any
 * other program or user on this machine can reach 127.0.0.1. kitout starts processes, runs docker and
 * git, and holds secrets; the key makes sure only the developer's own browser and terminal can.
 */
@ApplicationScoped
public class SessionToken {

    public static final String HEADER = "X-Kitout-Token";
    private static final Logger LOG = Logger.getLogger(SessionToken.class);
    private final String value;

    @Inject
    public SessionToken(KitoutPaths paths) {
        this(paths.stateDir().resolve("session-token"));
    }

    SessionToken(Path file) {
        this.value = readOrCreate(file);
    }

    static String readOrCreate(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                String kept = Files.readString(file).strip();
                if (kept.matches("[A-Za-z0-9_-]{43}")) {
                    return kept;
                }
            }
            String fresh = fresh();
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(tmp, fresh + "\n");
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return fresh;
        } catch (IOException | UnsupportedOperationException e) {
            LOG.debugf("Session key not kept (%s); a new one is used until kitout stops", e.getMessage());
            return fresh();
        }
    }

    private static String fresh() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String value() {
        return value;
    }

    /** Constant-time comparison, so response timing reveals nothing about the token. */
    public boolean matches(String candidate) {
        return candidate != null
                && MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }
}
