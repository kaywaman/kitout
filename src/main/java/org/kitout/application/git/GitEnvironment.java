package org.kitout.application.git;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.ConfigRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The environment for every git command that talks to GitLab.
 *
 * <ul>
 *   <li>{@code GIT_TERMINAL_PROMPT=0}: a missing credential fails at once instead of hanging</li>
 *   <li>{@code GIT_ASKPASS}: only when kitout holds a fallback token; points at a tiny script that
 *       runs {@code kitout askpass}, which answers from the keyring</li>
 *   <li>SSH runs in batch mode, so an unknown host key fails instead of waiting for "yes"</li>
 * </ul>
 */
@ApplicationScoped
public class GitEnvironment {

    private static final Logger LOG = Logger.getLogger(GitEnvironment.class);

    /** Variables an IDE terminal may set that would open a dialog from a background process. */
    public static final List<String> REMOVE = List.of("GIT_ASKPASS", "SSH_ASKPASS", "VSCODE_GIT_ASKPASS_MAIN",
            "VSCODE_GIT_ASKPASS_NODE", "VSCODE_GIT_IPC_HANDLE");

    private final CredentialService credentials;
    private final ConfigRepository config;
    private final KitoutPaths paths;
    private final SelfCommand self;

    public GitEnvironment(CredentialService credentials, ConfigRepository config, KitoutPaths paths, SelfCommand self) {
        this.credentials = credentials;
        this.config = config;
        this.paths = paths;
        this.self = self;
    }

    public Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put("GIT_TERMINAL_PROMPT", "0");
        if (System.getenv("GIT_SSH_COMMAND") == null) {
            env.put("GIT_SSH_COMMAND", "ssh -o BatchMode=yes -o ConnectTimeout=10");
        }
        String host = config.load().gitlab().host();
        if (!host.isEmpty() && credentials.hasFallbackToken(host)) {
            ensureAskpassScript().ifPresent(script -> {
                env.put("GIT_ASKPASS", script.toString());
                env.put("KITOUT_ASKPASS_HOST", host);
            });
        }
        return env;
    }

    /** Removed before {@link #env()} is applied, so kitout's own GIT_ASKPASS wins. */
    public List<String> removed() {
        return REMOVE;
    }

    private java.util.Optional<Path> ensureAskpassScript() {
        Path script = paths.askpassScript();
        List<String> cmd = self.command();
        if (cmd.isEmpty()) {
            return java.util.Optional.empty();
        }
        StringBuilder sb = new StringBuilder("#!/bin/sh\n# Written by kitout. Lets git ask kitout for the git token.\nexec");
        cmd.forEach(part -> sb.append(' ').append(shellQuote(part)));
        sb.append(" askpass \"$@\"\n");
        try {
            Files.createDirectories(script.getParent());
            String content = sb.toString();
            if (!Files.exists(script) || !Files.readString(script).equals(content)) {
                Files.writeString(script, content);
                Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
            }
            return java.util.Optional.of(script);
        } catch (IOException e) {
            LOG.warnf("Cannot write the askpass helper %s: %s", script, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /** Single-quote for /bin/sh. The values are kitout's own install paths, not user input. */
    static String shellQuote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }
}
