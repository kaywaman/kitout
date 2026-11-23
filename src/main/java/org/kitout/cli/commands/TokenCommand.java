package org.kitout.cli.commands;

import org.kitout.application.config.ConfigRepository;
import org.kitout.application.git.CredentialService;
import org.kitout.cli.Console;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

/**
 * {@code kitout token status|set|delete}: kitout's fallback token. The recommended place for the
 * token is git's credential helper; this is for developers who do not have one.
 */
@CommandLine.Command(name = "token", description = "Show where the git token comes from, or set kitout's fallback token.",
        subcommands = {TokenCommand.Set.class, TokenCommand.Delete.class})
public class TokenCommand implements Callable<Integer> {

    @Inject
    CredentialService credentials;

    @Inject
    ConfigRepository config;

    @Override
    public Integer call() {
        String host = host(config);
        Console.out("Git host: " + host);
        Console.out("Token source: " + switch (credentials.lookup(host).source()) {
            case GIT_CREDENTIAL_HELPER -> "git's credential helper (recommended)";
            case KITOUT_KEYRING -> "kitout's fallback entry in " + credentials.fallbackLocation();
            case NONE -> "none. Store it once with 'git fetch' in any repository (credential helper), or 'kitout token set'";
        });
        return Console.OK;
    }

    static String host(ConfigRepository config) {
        String host = config.load().gitlab().host();
        if (host.isEmpty()) {
            throw new IllegalStateException("Set the git host first (in the UI under Settings, Git).");
        }
        return host;
    }

    @CommandLine.Command(name = "set", description = "Store kitout's fallback token (read from the terminal, not echoed).")
    public static class Set implements Callable<Integer> {

        @Inject
        CredentialService credentials;

        @Inject
        ConfigRepository config;

        @Override
        public Integer call() throws IOException {
            String token;
            if (System.console() != null) {
                char[] chars = System.console().readPassword("Token for " + config.load().gitlab().host() + ": ");
                token = chars == null ? "" : new String(chars);
            } else {
                token = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            }
            credentials.storeFallbackToken(host(config), token);
            Console.out("Stored in " + credentials.fallbackLocation() + ".");
            return Console.OK;
        }
    }

    @CommandLine.Command(name = "delete", description = "Remove kitout's fallback token.")
    public static class Delete implements Callable<Integer> {

        @Inject
        CredentialService credentials;

        @Inject
        ConfigRepository config;

        @Override
        public Integer call() {
            credentials.deleteFallbackToken(host(config));
            Console.out("Removed.");
            return Console.OK;
        }
    }
}
