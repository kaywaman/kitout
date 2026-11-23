package org.kitout.domain.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What kitout reads from a Node project: its name, the npm scripts that start it, and the port its
 * dev server is likely to use. Pure and without a JSON library (the domain has none): it reads the
 * flat {@code "scripts"} object and the top-level {@code "name"}, which is all it needs.
 *
 * <p>The port is a guess, marked as such: an explicit {@code --port}/{@code -p} in the script, else
 * {@code server.port} in a Vite config, else {@code "port"} in angular.json, else the tool's default
 * (Vite 5173, Angular 4200, Next.js, Create React App and the rest 3000). The developer can set
 * the real one under Options.
 */
public final class NodePackage {

    /** Scripts that start a project, in the order kitout prefers them. */
    public static final List<String> START_SCRIPTS = List.of("dev", "start", "serve");

    private static final Pattern SCRIPTS = Pattern.compile("\"scripts\"\\s*:\\s*\\{([^}]*)}", Pattern.DOTALL);
    private static final Pattern ENTRY = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern NAME = Pattern.compile("^\\s*\\{\\s*(?:[^{}]*?,\\s*)?\"name\"\\s*:\\s*\"([^\"]+)\"", Pattern.DOTALL);
    private static final Pattern PORT_FLAG = Pattern.compile("(?:--port[=\\s]+|\\s-p\\s+)(\\d{2,5})\\b");
    private static final Pattern VITE_PORT = Pattern.compile("\\bport\\s*:\\s*(\\d{2,5})\\b");
    private static final Pattern ANGULAR_PORT = Pattern.compile("\"port\"\\s*:\\s*(\\d{2,5})\\b");

    private NodePackage() {
    }

    /** Every script, name to command, in file order. */
    public static Map<String, String> scripts(String packageJson) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher block = SCRIPTS.matcher(packageJson);
        if (block.find()) {
            Matcher e = ENTRY.matcher(block.group(1));
            while (e.find()) {
                result.put(e.group(1), e.group(2).replace("\\\"", "\""));
            }
        }
        return result;
    }

    /** The scripts that start the project ({@code dev}, {@code start}, {@code serve}), in that order; empty: not something to run. */
    public static List<String> startScripts(Map<String, String> scripts) {
        return START_SCRIPTS.stream().filter(scripts::containsKey).toList();
    }

    public static Optional<String> name(String packageJson) {
        Matcher m = NAME.matcher(packageJson);
        return m.find() ? Optional.of(m.group(1).replaceFirst("^@[^/]+/", "")) : Optional.empty();
    }

    /**
     * @param command    the start script's command, e.g. {@code vite --port 4173}
     * @param viteConfig the text of vite.config.* (empty if none)
     * @param angularJson the text of angular.json (empty if none)
     */
    public static int port(String command, String viteConfig, String angularJson) {
        Matcher flag = PORT_FLAG.matcher(" " + command);
        if (flag.find()) {
            return Integer.parseInt(flag.group(1));
        }
        if (command.contains("vite") || !viteConfig.isEmpty()) {
            Matcher m = VITE_PORT.matcher(viteConfig);
            return m.find() ? Integer.parseInt(m.group(1)) : 5173;
        }
        if (command.contains("ng ") || command.startsWith("ng") || !angularJson.isEmpty()) {
            Matcher m = ANGULAR_PORT.matcher(angularJson);
            return m.find() ? Integer.parseInt(m.group(1)) : 4200;
        }
        return 3000;
    }
}
