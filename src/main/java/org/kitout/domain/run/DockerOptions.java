package org.kitout.domain.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extra {@code docker run} options a developer types for a service (e.g. {@code --memory 1g
 * --add-host db.local:10.0.0.5 -v ~/certs:/certs:ro}). Pure, so it is tested without Docker.
 *
 * <p>The text is split into arguments the way a shell would split it (spaces, single and double
 * quotes, backslash), but no shell ever runs: the arguments go into kitout's argument list between
 * its own options and the image. That is why only options are accepted: a bare word would become
 * the image, and the real image the command.
 *
 * <p>Options kitout sets itself are refused with the reason, because changing them would break
 * what kitout relies on: the container name and label (stop, re-adopt, clean up), host networking
 * (the {@code %dev} profile's {@code localhost} URLs), {@code --rm}, a foreground process (logs and
 * stop), and environment variables (set in the service's environment, so values never show up in
 * the process list).
 */
public final class DockerOptions {

    /** Options that take no value. Every other option takes one, joined by {@code =} or as the next argument. */
    static final Set<String> FLAGS = Set.of(
            "--privileged", "--init", "--read-only", "--interactive", "-i", "--publish-all", "-P",
            "--oom-kill-disable", "--no-healthcheck", "--disable-content-trust", "--quiet", "-q");

    /** Short options without a value; only these may be combined ({@code -iP}). */
    static final Set<Character> SHORT_FLAGS = Set.of('i', 'P', 'q');

    /** Options kitout sets itself, with the reason shown when one is typed. */
    static final Map<String, String> RESERVED = Map.ofEntries(
            Map.entry("--name", "kitout names the container, so it can stop and re-adopt it"),
            Map.entry("--label", "kitout labels the container, so it can find and clean it up"),
            Map.entry("-l", "kitout labels the container, so it can find and clean it up"),
            Map.entry("--network", "kitout runs images with --network host, so the dev profile's localhost URLs work as in the IDE"),
            Map.entry("--net", "kitout runs images with --network host, so the dev profile's localhost URLs work as in the IDE"),
            Map.entry("--publish", "with --network host the service's ports are already on this machine; -p has no effect"),
            Map.entry("-p", "with --network host the service's ports are already on this machine; -p has no effect"),
            Map.entry("--rm", "kitout already removes the container when it stops"),
            Map.entry("--detach", "kitout runs the container in the foreground, so it can show its log and stop it"),
            Map.entry("-d", "kitout runs the container in the foreground, so it can show its log and stop it"),
            Map.entry("--tty", "a terminal would mix control characters into the service log"),
            Map.entry("-t", "a terminal would mix control characters into the service log"),
            Map.entry("--env", "add environment variables in the service's environment (the tune button): values then never show in the process list"),
            Map.entry("-e", "add environment variables in the service's environment (the tune button): values then never show in the process list"),
            Map.entry("--env-file", "import the file in the service's environment (the tune button, Import .env) instead"));

    private DockerOptions() {
    }

    /**
     * Splits and checks the text.
     *
     * @return the arguments, in order; empty for empty text
     * @throws IllegalArgumentException with one sentence that says what to change
     */
    public static List<String> parse(String text) {
        List<String> args = split(text == null ? "" : text);
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (!arg.startsWith("-") || arg.equals("-") || arg.equals("--")) {
                throw new IllegalArgumentException("Only docker run options are allowed here, and '" + arg
                        + "' is not one: kitout adds the image itself. Write options as --option value or --option=value");
            }
            String name = arg.contains("=") ? arg.substring(0, arg.indexOf('=')) : arg;
            if (!name.startsWith("--") && name.length() > 2) {
                checkShortCluster(name);
                continue;
            }
            String reason = RESERVED.get(name);
            if (reason != null) {
                throw new IllegalArgumentException(name + " cannot be changed here: " + reason);
            }
            if (FLAGS.contains(name) || arg.contains("=")) {
                continue;
            }
            if (i + 1 >= args.size()) {
                throw new IllegalArgumentException(name + " needs a value, e.g. " + name + " <value>");
            }
            i++;   // the value
        }
        return List.copyOf(args);
    }

    /** {@code -iP}: every letter must be a flag; {@code -it} names -t, which is reserved. */
    private static void checkShortCluster(String cluster) {
        for (char c : cluster.substring(1).toCharArray()) {
            String single = "-" + c;
            String reason = RESERVED.get(single);
            if (reason != null) {
                throw new IllegalArgumentException(single + " (in " + cluster + ") cannot be used here: " + reason);
            }
            if (!SHORT_FLAGS.contains(c)) {
                throw new IllegalArgumentException("Write " + single + " on its own with its value, not combined in " + cluster);
            }
        }
    }

    /**
     * Splits like a POSIX shell, without expanding anything: whitespace separates, single quotes keep
     * everything, double quotes keep everything but a backslash before {@code "} or {@code \}, a
     * backslash outside quotes keeps the next character.
     */
    static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote == '\'') {
                if (c == '\'') {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (quote == '"') {
                if (c == '"') {
                    quote = 0;
                } else if (c == '\\' && i + 1 < text.length() && (text.charAt(i + 1) == '"' || text.charAt(i + 1) == '\\')) {
                    current.append(text.charAt(++i));
                } else {
                    current.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
            } else if (c == '\\' && i + 1 < text.length()) {
                current.append(text.charAt(++i));
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    out.add(current.toString());
                    current.setLength(0);
                    inWord = false;
                }
            } else {
                current.append(c);
                inWord = true;
            }
        }
        if (quote != 0) {
            throw new IllegalArgumentException("A " + (quote == '"' ? "double" : "single") + " quote is not closed");
        }
        if (inWord) {
            out.add(current.toString());
        }
        return out;
    }

    /**
     * The {@code --mount} value for a host folder: {@code type=bind,source=...,target=...[,readonly]}.
     * {@code --mount} instead of {@code -v}: it fails when the host path does not exist (rather than
     * creating an empty folder owned by root) and needs no escaping of colons.
     *
     * @param hostPath already expanded and absolute
     * @throws IllegalArgumentException for a path the mount syntax cannot carry, or a relative container path
     */
    public static String mount(String hostPath, String containerPath, boolean readOnly) {
        if (!hostPath.startsWith("/")) {
            throw new IllegalArgumentException("The host path " + hostPath + " must be absolute (or start with ~/)");
        }
        if (!containerPath.startsWith("/")) {
            throw new IllegalArgumentException("The container path " + containerPath + " must be absolute, e.g. /deployments/config");
        }
        for (String p : List.of(hostPath, containerPath)) {
            if (p.contains(",") || p.contains("\"") || p.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("The path " + p + " contains a comma, quote or control character, which docker's --mount cannot take");
            }
        }
        return "type=bind,source=" + hostPath + ",target=" + containerPath + (readOnly ? ",readonly" : "");
    }
}
